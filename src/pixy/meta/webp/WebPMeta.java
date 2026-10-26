/**
 * Copyright (C) 2026 by akitogo.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */

package pixy.meta.webp;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;

import pixy.image.tiff.IFD;
import pixy.image.tiff.Tag;
import pixy.image.tiff.TiffTag;
import pixy.io.IOUtils;
import pixy.meta.Metadata;
import pixy.meta.MetadataType;
import pixy.meta.exif.Exif;
import pixy.meta.exif.ExifTag;
import pixy.meta.exif.ExifThumbnail;
import pixy.meta.icc.ICCProfile;
import pixy.meta.jpeg.JpegExif;
import pixy.meta.xmp.XMP;
import pixy.string.XMLUtils;

/**
 * WebP image metadata reader and writer.
 * <p>
 * WebP stores metadata in chunks of its RIFF container: "ICCP" (ICC profile), "EXIF" (a TIFF
 * structure, as in a JPEG APP1 segment but without the "Exif\0\0" preamble) and "XMP " (an XMP
 * packet). Metadata chunks are only allowed in the extended file format, so inserting metadata
 * into a simple (lossy "VP8 " or lossless "VP8L" only) file converts it to the extended format by
 * adding a "VP8X" header chunk. The image data itself is copied unchanged.
 *
 * @see <a href="https://developers.google.com/speed/webp/docs/riff_container">WebP Container Specification</a>
 */
public class WebPMeta {
	private static final byte[] RIFF = {'R', 'I', 'F', 'F'};
	private static final byte[] WEBP = {'W', 'E', 'B', 'P'};
	private static final byte[] EXIF_PREAMBLE = {'E', 'x', 'i', 'f', 0, 0};
	// The RIFF size field is 32 bit unsigned and must be even
	private static final long MAX_RIFF_SIZE = 0xFFFFFFFEL;
	/**
	 * Largest chunk read into memory. The format allows 4 GB, but the largest WebP image is
	 * 16383 x 16383 pixels, and a file declaring more is far more likely corrupt or hostile.
	 */
	private static final int MAX_CHUNK_LENGTH = 512 * 1024 * 1024;
	// VP8X feature flags
	private static final int FLAG_ICC = 0x20;
	private static final int FLAG_ALPHA = 0x10;
	private static final int FLAG_EXIF = 0x08;
	private static final int FLAG_XMP = 0x04;

	// Obtain a logger instance
	private static final Logger LOGGER = LoggerFactory.getLogger(WebPMeta.class);

	private WebPMeta() {}

	/**
	 * Inserts EXIF into a WebP image, replacing any EXIF it has.
	 *
	 * @param is input image stream
	 * @param os output image stream
	 * @param exif EXIF to insert. A thumbnail is written if the Exif instance holds one; one
	 *        cannot be generated from a WebP image.
	 * @param update true to merge into the existing EXIF (new fields win), false to replace it
	 * @throws IOException
	 */
	public static void insertExif(InputStream is, OutputStream os, Exif exif, boolean update) throws IOException {
		List<WebPChunk> chunks = readChunks(is);
		Exif oldExif = null;
		if(update) {
			WebPChunk old = findChunk(chunks, WebPChunk.EXIF);
			if(old != null)
				oldExif = new JpegExif(stripExifPreamble(old.data()));
		}
		if(exif.isThumbnailRequired() && !exif.containsThumbnail() && (oldExif == null || !oldExif.containsThumbnail()))
			LOGGER.warn("EXIF thumbnail requested but cannot be generated from a WebP image; writing EXIF without thumbnail");
		Map<String, byte[]> replacements = new HashMap<String, byte[]>();
		replacements.put(WebPChunk.EXIF, serializeExif(exif, oldExif));
		writeChunks(rebuild(chunks, replacements), os);
	}

	/**
	 * Inserts an ICC profile into a WebP image, replacing any it has.
	 */
	public static void insertICCProfile(InputStream is, OutputStream os, byte[] iccProfile) throws IOException {
		if(iccProfile == null || iccProfile.length == 0)
			throw new IllegalArgumentException("ICC profile is null or empty");
		Map<String, byte[]> replacements = new HashMap<String, byte[]>();
		replacements.put(WebPChunk.ICCP, iccProfile);
		writeChunks(rebuild(readChunks(is), replacements), os);
	}

	/**
	 * Inserts XMP into a WebP image, replacing any it has. ExtendedXMP, if any, is merged into
	 * the one packet since WebP has no size limit that would require splitting it.
	 */
	public static void insertXMP(InputStream is, OutputStream os, XMP xmp) throws IOException {
		insertXMP(is, os, XMLUtils.serializeToString(xmp.getMergedDocument()).getBytes(StandardCharsets.UTF_8));
	}

	/**
	 * Inserts XMP into a WebP image, replacing any it has. The xpacket processing instructions
	 * are added around the given XMP.
	 */
	public static void insertXMP(InputStream is, OutputStream os, String xmp) throws IOException {
		Document doc = XMLUtils.createXML(xmp);
		if(doc == null) throw new IllegalArgumentException("Invalid XMP");
		XMLUtils.insertLeadingPI(doc, "xpacket", "begin='' id='W5M0MpCehiHzreSzNTczkc9d'");
		XMLUtils.insertTrailingPI(doc, "xpacket", "end='r'");
		insertXMP(is, os, XMLUtils.serializeToString(doc).getBytes(StandardCharsets.UTF_8));
	}

	private static void insertXMP(InputStream is, OutputStream os, byte[] xmp) throws IOException {
		Map<String, byte[]> replacements = new HashMap<String, byte[]>();
		replacements.put(WebPChunk.XMP, xmp);
		writeChunks(rebuild(readChunks(is), replacements), os);
	}

	/**
	 * Reads the metadata of a WebP image: EXIF, XMP and ICC profile.
	 */
	public static Map<MetadataType, Metadata> readMetadata(InputStream is) throws IOException {
		Map<MetadataType, Metadata> metadataMap = new HashMap<MetadataType, Metadata>();
		for(WebPChunk chunk : readChunks(is)) {
			String fourCC = chunk.getFourCC();
			// Only the first chunk of each kind counts, as for every WebP reader
			if(fourCC.equals(WebPChunk.EXIF) && !metadataMap.containsKey(MetadataType.EXIF)) {
				byte[] tiff = stripExifPreamble(chunk.data());
				if(tiff.length > 0) metadataMap.put(MetadataType.EXIF, new JpegExif(tiff));
			} else if(fourCC.equals(WebPChunk.XMP) && !metadataMap.containsKey(MetadataType.XMP)) {
				if(chunk.getLength() > 0) metadataMap.put(MetadataType.XMP, new WebPXMP(chunk.getData()));
			} else if(fourCC.equals(WebPChunk.ICCP) && !metadataMap.containsKey(MetadataType.ICC_PROFILE)) {
				if(chunk.getLength() > 0) metadataMap.put(MetadataType.ICC_PROFILE, new ICCProfile(chunk.getData()));
			}
			LOGGER.debug("WebP chunk {}", chunk);
		}

		return metadataMap;
	}

	public static Map<MetadataType, Metadata> removeMetadata(InputStream is, OutputStream os, MetadataType ... metadataTypes) throws IOException {
		return removeMetadata(new HashSet<MetadataType>(Arrays.asList(metadataTypes)), is, os);
	}

	/**
	 * Removes the EXIF, XMP and/or ICC profile chunks named by the given types; other types are
	 * not stored in WebP and ignored.
	 *
	 * @return the removed metadata
	 */
	public static Map<MetadataType, Metadata> removeMetadata(Set<MetadataType> metadataTypes, InputStream is, OutputStream os) throws IOException {
		List<WebPChunk> chunks = readChunks(is);
		Map<MetadataType, Metadata> removed = new HashMap<MetadataType, Metadata>();
		Map<String, byte[]> replacements = new HashMap<String, byte[]>();
		WebPChunk chunk;
		if(metadataTypes.contains(MetadataType.EXIF)) {
			replacements.put(WebPChunk.EXIF, null);
			if((chunk = findChunk(chunks, WebPChunk.EXIF)) != null)
				removed.put(MetadataType.EXIF, new JpegExif(stripExifPreamble(chunk.data())));
		}
		if(metadataTypes.contains(MetadataType.XMP)) {
			replacements.put(WebPChunk.XMP, null);
			if((chunk = findChunk(chunks, WebPChunk.XMP)) != null)
				removed.put(MetadataType.XMP, new WebPXMP(chunk.getData()));
		}
		if(metadataTypes.contains(MetadataType.ICC_PROFILE)) {
			replacements.put(WebPChunk.ICCP, null);
			if((chunk = findChunk(chunks, WebPChunk.ICCP)) != null)
				removed.put(MetadataType.ICC_PROFILE, new ICCProfile(chunk.getData()));
		}
		writeChunks(rebuild(chunks, replacements), os);

		return removed;
	}

	/**
	 * Reads all chunks of a WebP file. Data after the end of the RIFF container is ignored.
	 *
	 * @throws IOException if the stream is not a WebP file, or a chunk is truncated or declares a
	 *         length larger than its container
	 */
	public static List<WebPChunk> readChunks(InputStream is) throws IOException {
		byte[] header = new byte[12];
		IOUtils.readFully(is, header);
		if(!Arrays.equals(Arrays.copyOfRange(header, 0, 4), RIFF) || !Arrays.equals(Arrays.copyOfRange(header, 8, 12), WEBP))
			throw new IOException("Invalid WebP signature");
		long riffSize = IOUtils.readUnsignedInt(header, 4);
		if(riffSize < 4)
			throw new IOException("Invalid WebP RIFF size: " + riffSize);
		long remaining = riffSize - 4; // "WEBP" counts towards the RIFF size
		List<WebPChunk> chunks = new ArrayList<WebPChunk>();
		byte[] chunkHeader = new byte[8];
		while(remaining >= 8) {
			int read = readAtMost(is, chunkHeader);
			if(read == 0) break; // Container size overstated, the file simply ends here
			if(read < 8) throw new IOException("Truncated WebP chunk header");
			String fourCC = new String(chunkHeader, 0, 4, StandardCharsets.ISO_8859_1);
			long length = IOUtils.readUnsignedInt(chunkHeader, 4);
			// Validate before allocating so a hostile length can't force a huge allocation
			if(length > remaining - 8 || length > MAX_CHUNK_LENGTH)
				throw new IOException("Invalid WebP chunk length for '" + fourCC + "': " + length);
			byte[] data = new byte[(int)length];
			try {
				IOUtils.readFully(is, data);
			} catch(EOFException e) {
				throw new IOException("Truncated WebP chunk '" + fourCC + "'", e);
			}
			remaining -= 8 + length;
			if((length & 1) != 0 && remaining > 0) {
				// RIFF pads odd sized chunks to an even length
				is.read();
				remaining--;
			}
			chunks.add(new WebPChunk(fourCC, data));
		}
		if(chunks.isEmpty())
			throw new IOException("WebP file has no chunks");

		return chunks;
	}

	/**
	 * Writes a WebP file from the given chunks, in the given order.
	 */
	public static void writeChunks(List<WebPChunk> chunks, OutputStream os) throws IOException {
		long riffSize = 4;
		for(WebPChunk chunk : chunks)
			riffSize += 8 + chunk.getLength() + (chunk.getLength() & 1);
		if(riffSize > MAX_RIFF_SIZE)
			throw new IOException("WebP file too large: " + riffSize + " bytes");
		os.write(RIFF);
		IOUtils.writeInt(os, (int)riffSize);
		os.write(WEBP);
		for(WebPChunk chunk : chunks) {
			os.write(chunk.getFourCC().getBytes(StandardCharsets.ISO_8859_1));
			IOUtils.writeInt(os, chunk.getLength());
			os.write(chunk.data());
			if((chunk.getLength() & 1) != 0)
				os.write(0);
		}
	}

	/**
	 * Applies metadata replacements and returns the chunks in the order the container
	 * specification requires: VP8X, ICCP, the image chunks (and any unknown chunks, in their
	 * original order), then EXIF and XMP.
	 *
	 * @param replacements new payload per metadata FourCC; a null value removes the chunk
	 */
	private static List<WebPChunk> rebuild(List<WebPChunk> chunks, Map<String, byte[]> replacements) throws IOException {
		WebPChunk vp8x = null, iccp = null, exif = null, xmp = null;
		List<WebPChunk> image = new ArrayList<WebPChunk>();
		for(WebPChunk chunk : chunks) {
			String fourCC = chunk.getFourCC();
			// Keep the first of each; duplicates are invalid and ignored by readers
			if(fourCC.equals(WebPChunk.VP8X)) { if(vp8x == null) vp8x = chunk; }
			else if(fourCC.equals(WebPChunk.ICCP)) { if(iccp == null) iccp = chunk; }
			else if(fourCC.equals(WebPChunk.EXIF)) { if(exif == null) exif = chunk; }
			else if(fourCC.equals(WebPChunk.XMP)) { if(xmp == null) xmp = chunk; }
			else image.add(chunk);
		}
		iccp = replace(iccp, WebPChunk.ICCP, replacements);
		exif = replace(exif, WebPChunk.EXIF, replacements);
		xmp = replace(xmp, WebPChunk.XMP, replacements);

		List<WebPChunk> result = new ArrayList<WebPChunk>(image.size() + 4);
		if(vp8x == null && iccp == null && exif == null && xmp == null) {
			// Simple file format, nothing to add
			result.addAll(image);
			return result;
		}
		byte[] header;
		if(vp8x != null && vp8x.getLength() >= 10) {
			header = vp8x.getData(); // Keeps the canvas size and the alpha/animation flags
		} else {
			header = createVP8XHeader(image);
		}
		int flags = header[0] & ~(FLAG_ICC | FLAG_EXIF | FLAG_XMP);
		if(iccp != null) flags |= FLAG_ICC;
		if(exif != null) flags |= FLAG_EXIF;
		if(xmp != null) flags |= FLAG_XMP;
		header[0] = (byte)flags;

		result.add(new WebPChunk(WebPChunk.VP8X, header));
		if(iccp != null) result.add(iccp);
		result.addAll(image);
		if(exif != null) result.add(exif);
		if(xmp != null) result.add(xmp);

		return result;
	}

	private static WebPChunk replace(WebPChunk chunk, String fourCC, Map<String, byte[]> replacements) {
		if(!replacements.containsKey(fourCC)) return chunk;
		byte[] data = replacements.get(fourCC);
		return (data == null) ? null : new WebPChunk(fourCC, data);
	}

	/**
	 * Builds the 10 byte VP8X payload for a simple format file: flags, three reserved bytes, and
	 * the 24 bit canvas width and height minus one, both read from the image bitstream header.
	 */
	private static byte[] createVP8XHeader(List<WebPChunk> image) throws IOException {
		int width = -1, height = -1, flags = 0;
		for(WebPChunk chunk : image) {
			byte[] data = chunk.data();
			if(chunk.getFourCC().equals(WebPChunk.VP8) && data.length >= 10) {
				// VP8 key frame: 3 byte frame tag, start code 9d 01 2a, then 14 bit width and height
				if((data[3] & 0xff) != 0x9d || (data[4] & 0xff) != 0x01 || (data[5] & 0xff) != 0x2a)
					throw new IOException("Invalid VP8 bitstream header");
				width = ((data[6] & 0xff) | (data[7] & 0xff) << 8) & 0x3fff;
				height = ((data[8] & 0xff) | (data[9] & 0xff) << 8) & 0x3fff;
				break;
			} else if(chunk.getFourCC().equals(WebPChunk.VP8L) && data.length >= 5) {
				// VP8L: signature 0x2f, then 14 bit width-1, 14 bit height-1 and the alpha_is_used bit
				if((data[0] & 0xff) != 0x2f)
					throw new IOException("Invalid VP8L bitstream header");
				int bits = (data[1] & 0xff) | (data[2] & 0xff) << 8 | (data[3] & 0xff) << 16 | (data[4] & 0xff) << 24;
				width = (bits & 0x3fff) + 1;
				height = ((bits >>> 14) & 0x3fff) + 1;
				if(((bits >>> 28) & 1) != 0) flags |= FLAG_ALPHA;
				break;
			} else if(chunk.getFourCC().equals(WebPChunk.ANMF) || chunk.getFourCC().equals(WebPChunk.ANIM)) {
				// Animation is only valid in the extended format, so this file is broken
				throw new IOException("Animated WebP without VP8X chunk");
			}
		}
		if(width <= 0 || height <= 0)
			throw new IOException("Cannot determine WebP canvas size, no VP8/VP8L image chunk found");
		byte[] header = new byte[10];
		header[0] = (byte)flags;
		writeInt24LE(header, 4, width - 1);
		writeInt24LE(header, 7, height - 1);

		return header;
	}

	/**
	 * Serializes EXIF to the raw TIFF structure stored in the EXIF chunk.
	 *
	 * @param oldExif existing EXIF to merge into, or null
	 */
	private static byte[] serializeExif(Exif exif, Exif oldExif) throws IOException {
		JpegExif out = new JpegExif();
		Exif base = (oldExif != null) ? oldExif : exif;
		out.setPreferredEndian(base.getPreferredEndian());
		// Rebuild fresh IFDs: the getters return read-only views, and the sub IFD pointer fields
		// are recreated by JpegExif.write()
		IFD imageIFD = merge(oldExif == null ? null : oldExif.getImageIFD(), exif.getImageIFD(), TiffTag.EXIF_SUB_IFD, TiffTag.GPS_SUB_IFD);
		IFD exifIFD = merge(oldExif == null ? null : oldExif.getExifIFD(), exif.getExifIFD(), ExifTag.EXIF_INTEROPERABILITY_OFFSET);
		IFD gpsIFD = merge(oldExif == null ? null : oldExif.getGPSIFD(), exif.getGPSIFD());
		IFD interopIFD = merge(oldExif == null ? null : oldExif.getInteropIFD(), exif.getInteropIFD());
		if(imageIFD != null) out.setImageIFD(imageIFD);
		out.setExifIFD(exifIFD);
		out.setGPSIFD(gpsIFD);
		out.setInteropIFD(interopIFD);
		ExifThumbnail thumbnail = exif.getThumbnail();
		if(thumbnail == null && oldExif != null) thumbnail = oldExif.getThumbnail();
		if(thumbnail != null && thumbnail.containsImage()) out.setThumbnail(thumbnail);

		ByteArrayOutputStream bout = new ByteArrayOutputStream();
		out.write(bout);
		byte[] segment = bout.toByteArray();
		// JpegExif writes an APP1 segment: marker (2), length (2), "Exif\0\0" (6), TIFF structure
		return Arrays.copyOfRange(segment, 10, segment.length);
	}

	private static IFD merge(IFD oldIFD, IFD newIFD, Tag ... skipTags) {
		if(oldIFD == null && newIFD == null) return null;
		IFD merged = new IFD();
		if(oldIFD != null) merged.addFields(oldIFD.getFields());
		if(newIFD != null) merged.addFields(newIFD.getFields());
		for(Tag tag : skipTags)
			merged.removeField(tag);
		return merged;
	}

	private static WebPChunk findChunk(Collection<WebPChunk> chunks, String fourCC) {
		for(WebPChunk chunk : chunks) {
			if(chunk.getFourCC().equals(fourCC)) return chunk;
		}
		return null;
	}

	/**
	 * Some writers copy a whole JPEG APP1 payload into the EXIF chunk; accept both forms.
	 */
	private static byte[] stripExifPreamble(byte[] data) {
		if(data.length >= EXIF_PREAMBLE.length && Arrays.equals(Arrays.copyOf(data, EXIF_PREAMBLE.length), EXIF_PREAMBLE))
			return Arrays.copyOfRange(data, EXIF_PREAMBLE.length, data.length);
		return data;
	}

	// Fills as much of the buffer as the stream has; returns the number of bytes read
	private static int readAtMost(InputStream is, byte[] buf) throws IOException {
		int n = 0;
		while(n < buf.length) {
			int count = is.read(buf, n, buf.length - n);
			if(count < 0) break;
			n += count;
		}
		return n;
	}

	private static void writeInt24LE(byte[] buf, int offset, int value) {
		buf[offset] = (byte)value;
		buf[offset + 1] = (byte)(value >>> 8);
		buf[offset + 2] = (byte)(value >>> 16);
	}
}
