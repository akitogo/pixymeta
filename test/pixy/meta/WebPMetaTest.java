package pixy.meta;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static pixy.meta.CrossValidationTest.transform;

import java.awt.color.ColorSpace;
import java.awt.color.ICC_Profile;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.drew.metadata.exif.ExifIFD0Directory;
import com.drew.metadata.icc.IccDirectory;
import com.drew.metadata.webp.WebpDirectory;
import com.drew.metadata.xmp.XmpDirectory;

import pixy.image.ImageType;
import pixy.image.tiff.FieldType;
import pixy.image.tiff.TiffTag;
import pixy.io.PeekHeadInputStream;
import pixy.meta.exif.Exif;
import pixy.meta.iptc.IPTCApplicationTag;
import pixy.meta.iptc.IPTCDataSet;
import pixy.meta.jpeg.JpegExif;
import pixy.meta.webp.WebPChunk;
import pixy.meta.webp.WebPMeta;
import pixy.util.MetadataUtils;

/**
 * WebP metadata support: reading, inserting and removing EXIF, XMP and ICC profiles, and the
 * conversion of simple format files to the extended format this requires. Written files are
 * verified with metadata-extractor as an independent reader.
 */
public class WebPMetaTest {

	// 1x1 images in each WebP flavour (the well known feature detection samples)
	private static final byte[] LOSSY = Base64.getDecoder().decode(
			"UklGRiIAAABXRUJQVlA4IBYAAAAwAQCdASoBAAEADsD+JaQAA3AAAAAA");
	private static final byte[] LOSSLESS = Base64.getDecoder().decode(
			"UklGRhoAAABXRUJQVlA4TA0AAAAvAAAAEAcQERGIiP4HAA==");
	private static final byte[] ALPHA = Base64.getDecoder().decode(
			"UklGRkoAAABXRUJQVlA4WAoAAAAQAAAAAAAAAAAAQUxQSAwAAAARBxAR/Q9ERP8DAABWUDggGAAAABQBAJ0BKgEAAQAAAP4AAA3AAP7mtQAAAA==");
	private static final byte[] ANIMATED = Base64.getDecoder().decode(
			"UklGRlIAAABXRUJQVlA4WAoAAAASAAAAAAAAAAAAQU5JTQYAAAD/////AABBTk1GJgAAAAAAAAAAAAAAAAAAAGQAAABWUDhMDQAAAC8AAAAQBxAREYiI/gcA");

	private static final String XMP = "<x:xmpmeta xmlns:x='adobe:ns:meta/'>"
			+ "<rdf:RDF xmlns:rdf='http://www.w3.org/1999/02/22-rdf-syntax-ns#'>"
			+ "<rdf:Description rdf:about='' xmlns:xmp='http://ns.adobe.com/xap/1.0/' xmp:CreatorTool='pixymeta-test'/>"
			+ "</rdf:RDF></x:xmpmeta>";

	private static final int FLAG_ICC = 0x20, FLAG_ALPHA = 0x10, FLAG_EXIF = 0x08, FLAG_XMP = 0x04, FLAG_ANIMATION = 0x02;

	@Test
	@DisplayName("WebP is detected from the RIFF form type, not mistaken for other RIFF files")
	void detection() throws IOException {
		assertEquals(ImageType.WEBP, guess(LOSSY));
		byte[] wave = LOSSY.clone();
		wave[8] = 'W'; wave[9] = 'A'; wave[10] = 'V'; wave[11] = 'E';
		assertEquals(ImageType.UNKNOWN, guess(wave));
		// A file shorter than the WebP header still gets the 4 byte magic check
		assertEquals(ImageType.UNKNOWN, guess(new byte[] {'R', 'I', 'F', 'F', 0, 0}));
		assertEquals(ImageType.JPG, guess(new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xe0}));
	}

	@Test
	@DisplayName("Simple lossy file: inserting XMP adds a VP8X header with the canvas size")
	void lossyBecomesExtended() throws IOException {
		byte[] out = transform(LOSSY, (is, os) -> Metadata.insertXMP(is, os, XMP));

		assertEquals(List.of("VP8X", "VP8 ", "XMP "), fourCCs(out));
		byte[] vp8x = chunk(out, WebPChunk.VP8X);
		assertEquals(FLAG_XMP, vp8x[0]);
		assertCanvas(vp8x, 1, 1);
		assertImageChunksUnchanged(LOSSY, out);

		com.drew.metadata.Metadata me = MetadataExtractorOracle.read(out);
		assertEquals("pixymeta-test", me.getFirstDirectoryOfType(XmpDirectory.class).getXmpProperties().get("xmp:CreatorTool"));
		assertEquals(1, me.getFirstDirectoryOfType(WebpDirectory.class).getInteger(WebpDirectory.TAG_IMAGE_WIDTH));
		assertEquals(List.of(), MetadataExtractorOracle.errors(me));
	}

	@Test
	@DisplayName("Simple lossless file: the alpha flag is taken from the VP8L header")
	void losslessAlphaFlag() throws IOException {
		byte[] out = transform(LOSSLESS, (is, os) -> Metadata.insertXMP(is, os, XMP));

		assertEquals(List.of("VP8X", "VP8L", "XMP "), fourCCs(out));
		byte[] vp8x = chunk(out, WebPChunk.VP8X);
		assertEquals(FLAG_XMP | FLAG_ALPHA, vp8x[0]);
		assertCanvas(vp8x, 1, 1);
	}

	@Test
	@DisplayName("Extended file: ICCP goes right after VP8X, ALPH and the alpha flag are kept")
	void extendedKeepsAlpha() throws IOException {
		byte[] icc = ICC_Profile.getInstance(ColorSpace.CS_sRGB).getData();
		byte[] out = transform(ALPHA, (is, os) -> Metadata.insertICCProfile(is, os, icc));

		assertEquals(List.of("VP8X", "ICCP", "ALPH", "VP8 "), fourCCs(out));
		assertEquals(FLAG_ALPHA | FLAG_ICC, chunk(out, WebPChunk.VP8X)[0]);
		assertImageChunksUnchanged(ALPHA, out);
		IccDirectory dir = MetadataExtractorOracle.read(out).getFirstDirectoryOfType(IccDirectory.class);
		assertNotNull(dir);
		assertEquals(icc.length, dir.getInteger(IccDirectory.TAG_PROFILE_BYTE_COUNT));
	}

	@Test
	@DisplayName("Animated file: ANIM/ANMF and the animation flag are kept, EXIF goes last")
	void animatedKeepsFrames() throws IOException {
		byte[] out = transform(ANIMATED, (is, os) -> Metadata.insertExif(is, os, exif("Animator"), false));

		assertEquals(List.of("VP8X", "ANIM", "ANMF", "EXIF"), fourCCs(out));
		int originalFlags = chunk(ANIMATED, WebPChunk.VP8X)[0];
		assertEquals(FLAG_ANIMATION, originalFlags & FLAG_ANIMATION);
		assertEquals(originalFlags | FLAG_EXIF, chunk(out, WebPChunk.VP8X)[0]);
		assertImageChunksUnchanged(ANIMATED, out);
	}

	@Test
	@DisplayName("EXIF round trip; update merges into the existing EXIF")
	void exifRoundTripAndUpdate() throws Exception {
		byte[] first = transform(LOSSY, (is, os) -> Metadata.insertExif(is, os, exif("Jane Tester"), false));
		Exif second = new JpegExif();
		second.addImageField(TiffTag.COPYRIGHT, FieldType.ASCII, "(c) pixymeta");
		byte[] merged = transform(first, (is, os) -> Metadata.insertExif(is, os, second, true));
		byte[] replaced = transform(first, (is, os) -> Metadata.insertExif(is, os, second, false));

		ExifIFD0Directory ifd0 = MetadataExtractorOracle.read(merged).getFirstDirectoryOfType(ExifIFD0Directory.class);
		assertEquals("Jane Tester", ifd0.getString(ExifIFD0Directory.TAG_ARTIST));
		assertEquals("(c) pixymeta", ifd0.getString(ExifIFD0Directory.TAG_COPYRIGHT));
		assertEquals("pixymeta", ifd0.getString(ExifIFD0Directory.TAG_MAKE));
		ifd0 = MetadataExtractorOracle.read(replaced).getFirstDirectoryOfType(ExifIFD0Directory.class);
		assertNull(ifd0.getString(ExifIFD0Directory.TAG_ARTIST));
		assertEquals("(c) pixymeta", ifd0.getString(ExifIFD0Directory.TAG_COPYRIGHT));
		assertEquals(1, fourCCs(merged).stream().filter("EXIF"::equals).count());

		// PIXYMETA reads back what it wrote
		Exif read = (Exif) Metadata.readMetadata(new ByteArrayInputStream(merged)).get(MetadataType.EXIF);
		assertEquals("Jane Tester", read.getImageIFD().getField(TiffTag.ARTIST).getDataAsString());
	}

	@Test
	@DisplayName("All three metadata kinds together, read back by PIXYMETA and metadata-extractor")
	void allMetadataKinds() throws IOException {
		byte[] icc = ICC_Profile.getInstance(ColorSpace.CS_sRGB).getData();
		byte[] out = transform(LOSSY, (is, os) -> Metadata.insertExif(is, os, exif("Jane Tester"), false));
		out = transform(out, (is, os) -> Metadata.insertXMP(is, os, XMP));
		out = transform(out, (is, os) -> Metadata.insertICCProfile(is, os, icc));

		assertEquals(List.of("VP8X", "ICCP", "VP8 ", "EXIF", "XMP "), fourCCs(out));
		assertEquals(FLAG_ICC | FLAG_EXIF | FLAG_XMP, chunk(out, WebPChunk.VP8X)[0]);
		Map<MetadataType, Metadata> read = Metadata.readMetadata(new ByteArrayInputStream(out));
		assertTrue(read.keySet().containsAll(List.of(MetadataType.EXIF, MetadataType.XMP, MetadataType.ICC_PROFILE)));
		assertEquals(List.of(), MetadataExtractorOracle.errors(MetadataExtractorOracle.read(out)));
	}

	@Test
	@DisplayName("Removing metadata drops the chunks and clears their VP8X flags")
	void removeMetadata() throws IOException {
		byte[] full = transform(ALPHA, (is, os) -> Metadata.insertExif(is, os, exif("Jane Tester"), false));
		full = transform(full, (is, os) -> Metadata.insertXMP(is, os, XMP));

		ByteArrayOutputStream os = new ByteArrayOutputStream();
		Map<MetadataType, Metadata> removed = Metadata.removeMetadata(new ByteArrayInputStream(full), os, MetadataType.EXIF, MetadataType.XMP);
		byte[] out = os.toByteArray();

		assertEquals(List.of("VP8X", "ALPH", "VP8 "), fourCCs(out));
		assertEquals(FLAG_ALPHA, chunk(out, WebPChunk.VP8X)[0]);
		assertTrue(removed.containsKey(MetadataType.EXIF) && removed.containsKey(MetadataType.XMP));
		assertTrue(Metadata.readMetadata(new ByteArrayInputStream(out)).isEmpty());
	}

	@Test
	@DisplayName("Odd sized chunks are padded and the RIFF size stays consistent")
	void oddChunkPadding() throws IOException {
		// The serialized packet length isn't known up front: one of two lengths differing by one is odd
		byte[] out = transform(LOSSY, (is, os) -> Metadata.insertXMP(is, os, XMP));
		byte[] out2 = transform(LOSSY, (is, os) -> Metadata.insertXMP(is, os, XMP.replace("pixymeta-test", "pixymeta-tests")));
		assertEquals(1, (chunk(out, WebPChunk.XMP).length + chunk(out2, WebPChunk.XMP).length) % 2);

		for (byte[] file : List.of(out, out2)) {
			assertEquals(0, file.length % 2);
			assertEquals(file.length - 8, ByteBuffer.wrap(file, 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt());
			assertEquals(List.of(), MetadataExtractorOracle.errors(MetadataExtractorOracle.read(file)));
		}
	}

	@Test
	@DisplayName("EXIF chunks with a JPEG style \"Exif\\0\\0\" preamble are read too")
	void exifPreambleTolerated() throws IOException {
		byte[] written = transform(LOSSY, (is, os) -> Metadata.insertExif(is, os, exif("Jane Tester"), false));
		List<WebPChunk> chunks = new ArrayList<>(WebPMeta.readChunks(new ByteArrayInputStream(written)));
		for (int i = 0; i < chunks.size(); i++) {
			if (chunks.get(i).getFourCC().equals(WebPChunk.EXIF)) {
				byte[] tiff = chunks.get(i).getData();
				byte[] withPreamble = new byte[tiff.length + 6];
				System.arraycopy("Exif\0\0".getBytes(), 0, withPreamble, 0, 6);
				System.arraycopy(tiff, 0, withPreamble, 6, tiff.length);
				chunks.set(i, new WebPChunk(WebPChunk.EXIF, withPreamble));
			}
		}
		ByteArrayOutputStream os = new ByteArrayOutputStream();
		WebPMeta.writeChunks(chunks, os);

		Exif exif = (Exif) Metadata.readMetadata(new ByteArrayInputStream(os.toByteArray())).get(MetadataType.EXIF);
		assertEquals("Jane Tester", exif.getImageIFD().getField(TiffTag.ARTIST).getDataAsString());
	}

	@Test
	@DisplayName("IPTC, IRB and comments have no place in WebP: the image is copied unchanged")
	void unsupportedOperationsCopyInput() throws IOException {
		assertArrayEquals(ALPHA, transform(ALPHA, (is, os) -> Metadata.insertIPTC(is, os,
				List.of(new IPTCDataSet(IPTCApplicationTag.KEY_WORDS, "x")), false)));
		assertArrayEquals(ALPHA, transform(ALPHA, (is, os) -> Metadata.insertIRB(is, os, List.of(), false)));
		assertArrayEquals(ALPHA, transform(ALPHA, (is, os) -> Metadata.insertComments(is, os, List.of("x"))));
	}

	// ---- malformed input ----

	@Test
	@DisplayName("A chunk length beyond the RIFF container is rejected before allocating")
	void hugeChunkLengthRejected() {
		byte[] bad = LOSSY.clone();
		putIntLE(bad, 16, 0x7ffffff0); // VP8 chunk length
		assertThrows(IOException.class, () -> Metadata.readMetadata(new ByteArrayInputStream(bad)));
	}

	@Test
	@DisplayName("A truncated chunk is rejected with IOException")
	void truncatedChunkRejected() {
		byte[] bad = java.util.Arrays.copyOf(LOSSY, LOSSY.length - 6);
		assertThrows(IOException.class, () -> Metadata.readMetadata(new ByteArrayInputStream(bad)));
	}

	@Test
	@DisplayName("A RIFF size smaller than the form type is rejected with IOException")
	void tinyRiffSizeRejected() {
		byte[] bad = LOSSY.clone();
		putIntLE(bad, 4, 2);
		assertThrows(IOException.class, () -> Metadata.readMetadata(new ByteArrayInputStream(bad)));
	}

	@Test
	@DisplayName("A file without image chunk cannot get metadata (no canvas size to write)")
	void noImageChunkRejected() throws IOException {
		ByteArrayOutputStream os = new ByteArrayOutputStream();
		WebPMeta.writeChunks(List.of(new WebPChunk("JUNK", new byte[3])), os);
		byte[] junk = os.toByteArray();
		assertFalse(Metadata.readMetadata(new ByteArrayInputStream(junk)).containsKey(MetadataType.XMP));
		assertThrows(IOException.class, () -> transform(junk, (is, out) -> Metadata.insertXMP(is, out, XMP)));
	}

	// ----------------------------- helpers -----------------------------

	private static Exif exif(String artist) {
		Exif exif = new JpegExif();
		exif.addImageField(TiffTag.MAKE, FieldType.ASCII, "pixymeta");
		exif.addImageField(TiffTag.ARTIST, FieldType.ASCII, artist);
		return exif;
	}

	private static ImageType guess(byte[] bytes) throws IOException {
		return MetadataUtils.guessImageType(new PeekHeadInputStream(new ByteArrayInputStream(bytes),
				MetadataUtils.IMAGE_MAGIC_NUMBER_LEN, MetadataUtils.IMAGE_HEADER_PEEK_LEN));
	}

	private static List<String> fourCCs(byte[] webp) throws IOException {
		List<String> fourCCs = new ArrayList<>();
		for (WebPChunk chunk : WebPMeta.readChunks(new ByteArrayInputStream(webp)))
			fourCCs.add(chunk.getFourCC());
		return fourCCs;
	}

	private static byte[] chunk(byte[] webp, String fourCC) throws IOException {
		for (WebPChunk chunk : WebPMeta.readChunks(new ByteArrayInputStream(webp))) {
			if (chunk.getFourCC().equals(fourCC))
				return chunk.getData();
		}
		return null;
	}

	/** The image data (every chunk that is not metadata or VP8X) must be copied byte for byte. */
	private static void assertImageChunksUnchanged(byte[] original, byte[] written) throws IOException {
		assertEquals(imageChunks(original), imageChunks(written));
	}

	private static List<String> imageChunks(byte[] webp) throws IOException {
		List<String> chunks = new ArrayList<>();
		for (WebPChunk chunk : WebPMeta.readChunks(new ByteArrayInputStream(webp))) {
			String fourCC = chunk.getFourCC();
			if (!List.of(WebPChunk.VP8X, WebPChunk.ICCP, WebPChunk.EXIF, WebPChunk.XMP).contains(fourCC))
				chunks.add(fourCC + Base64.getEncoder().encodeToString(chunk.getData()));
		}
		return chunks;
	}

	private static void assertCanvas(byte[] vp8x, int width, int height) {
		int w = (vp8x[4] & 0xff | (vp8x[5] & 0xff) << 8 | (vp8x[6] & 0xff) << 16) + 1;
		int h = (vp8x[7] & 0xff | (vp8x[8] & 0xff) << 8 | (vp8x[9] & 0xff) << 16) + 1;
		assertEquals(width, w);
		assertEquals(height, h);
	}

	private static void putIntLE(byte[] buf, int off, int v) {
		ByteBuffer.wrap(buf, off, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(v);
	}
}
