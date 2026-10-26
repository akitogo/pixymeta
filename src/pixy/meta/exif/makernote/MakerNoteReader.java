/**
 * Copyright (C) 2026 by akitogo.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * The maker note detection rules (signatures, IFD start and offset base per vendor) are ported
 * from ExifTiffHandler.processMakernote() of metadata-extractor
 * (https://github.com/drewnoakes/metadata-extractor), Copyright 2002-2019 Drew Noakes and
 * contributors, licensed under the Apache License, Version 2.0. See NOTICE.
 */

package pixy.meta.exif.makernote;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import pixy.image.tiff.FieldType;

/**
 * Decodes the vendor specific IFD structures stored in the EXIF MakerNote tag.
 * <p>
 * The decoder works on the maker note bytes alone. Many vendors store value offsets relative to
 * the enclosing TIFF header rather than to the maker note, so the caller passes the offset at
 * which the maker note sat inside that TIFF structure to translate them. A value that points
 * outside the maker note block is skipped.
 * <p>
 * The input is untrusted: every offset and count is bounds checked, cyclic IFD links are
 * detected, and the total work is capped, so a malformed maker note yields fewer or no entries
 * instead of an exception.
 */
public final class MakerNoteReader {
	// Upper bound on decoded entries for one maker note, guards against crafted input
	private static final int MAX_ENTRIES = 8192;
	// Olympus nests sub IFDs one level deep; allow a little slack, never unbounded recursion
	private static final int MAX_DEPTH = 4;
	// Canon stores several arrays of 16 bit values whose elements each have their own meaning.
	// They are split into individual synthetic tags at these bases, as metadata-extractor does.
	private static final int CANON_CAMERA_SETTINGS_ARRAY = 0x0001;
	private static final int CANON_FOCAL_LENGTH_ARRAY = 0x0002;
	private static final int CANON_SHOT_INFO_ARRAY = 0x0004;
	private static final int CANON_PANORAMA_ARRAY = 0x0005;
	private static final int CANON_AF_INFO_ARRAY = 0x0012;
	private static final int CANON_AF_INFO_BASE = 0xD200;
	private static final int CANON_AF_AREA_X_POSITIONS = CANON_AF_INFO_BASE + 8;
	private static final int CANON_AF_AREA_Y_POSITIONS = CANON_AF_INFO_BASE + 9;
	private static final int CANON_AF_POINTS_IN_FOCUS = CANON_AF_INFO_BASE + 10;
	// Minolta/Olympus camera settings blobs, split into big endian 32 bit values at this base
	private static final int OLYMPUS_CAMERA_SETTINGS_1 = 0x0001;
	private static final int OLYMPUS_CAMERA_SETTINGS_2 = 0x0003;
	private static final int OLYMPUS_CAMERA_SETTINGS_BASE = 0xF000;

	// Obtain a logger instance
	private static final Logger LOGGER = LoggerFactory.getLogger(MakerNoteReader.class);

	private final byte[] data;
	private final int makerNoteOffset;
	private final MakerNote makerNote = new MakerNote();
	private final Set<Integer> visitedIFDs = new HashSet<Integer>();
	private boolean bigEndian;
	private int entryCount;
	// Relocation mode, see relocate(): offsets are patched in this copy instead of decoded
	private byte[] relocated;
	private long relocationDelta;

	private MakerNoteReader(byte[] data, int makerNoteOffset, boolean bigEndian) {
		this.data = data;
		this.makerNoteOffset = makerNoteOffset;
		this.bigEndian = bigEndian;
	}

	/**
	 * Decodes a maker note.
	 *
	 * @param data the raw bytes of the MakerNote tag
	 * @param makerNoteOffset offset of the maker note inside the enclosing TIFF structure (i.e.
	 *        relative to the TIFF header), or -1 if unknown
	 * @param bigEndian byte order of the enclosing TIFF structure
	 * @param make camera make from IFD0, may be null
	 * @return the decoded maker note, or null if the format is not recognized or nothing
	 *         could be decoded
	 */
	public static MakerNote read(byte[] data, int makerNoteOffset, boolean bigEndian, String make) {
		if(data == null || data.length < 2) return null;
		MakerNoteReader reader = new MakerNoteReader(data, makerNoteOffset, bigEndian);
		try {
			if(!reader.decode(make == null ? null : make.trim())) return null;
		} catch(RuntimeException e) {
			// Every read below is bounds checked; this is a last line of defence so a decoder bug on
			// hostile input degrades to "no maker note" instead of failing the whole EXIF read.
			LOGGER.warn("Failed to decode maker note: {}", e.toString());
			return null;
		}
		if(reader.makerNote.getDirectories().isEmpty()) return null;

		return reader.makerNote;
	}

	/**
	 * Adjusts a maker note for being written at a different offset than it was read from.
	 * <p>
	 * Vendors such as Canon, Sony, Panasonic, Casio, Samsung or older Nikon and Olympus models
	 * store the offsets of the values inside their maker note relative to the TIFF header of the
	 * EXIF data. When the EXIF is rewritten and the maker note lands somewhere else, those offsets
	 * must be shifted by the same amount or the maker note becomes unreadable. Maker notes whose
	 * offsets are relative to themselves (Nikon type 2, Fujifilm, Apple, newer Olympus) are
	 * position independent and returned unchanged, as are unrecognized formats.
	 * 
	 * @param data the raw bytes of the MakerNote tag
	 * @param oldOffset offset relative to the TIFF header the maker note was read from, or -1
	 * @param newOffset offset relative to the TIFF header it will be written to
	 * @param bigEndian byte order of the TIFF structure it was read from
	 * @param make camera make from IFD0, may be null
	 * @return the adjusted maker note, or the input array if nothing needs to change
	 */
	public static byte[] relocate(byte[] data, int oldOffset, int newOffset, boolean bigEndian, String make) {
		if(data == null || data.length < 2 || oldOffset <= 0 || newOffset < 0 || oldOffset == newOffset) return data;
		MakerNoteReader reader = new MakerNoteReader(data, oldOffset, bigEndian);
		reader.relocated = data.clone();
		reader.relocationDelta = (long)newOffset - oldOffset;
		try {
			reader.decode(make == null ? null : make.trim());
		} catch(RuntimeException e) {
			LOGGER.warn("Failed to relocate maker note, writing it unchanged: {}", e.toString());
			return data;
		}
		return reader.relocated;
	}
	
	// Offset base used by vendors that store offsets relative to the enclosing TIFF header
	private int tiffBase() {
		return -makerNoteOffset;
	}

	private boolean decode(String make) {
		String upperMake = (make == null) ? "" : make.toUpperCase();
		boolean tiffBaseKnown = makerNoteOffset >= 0;

		if(startsWith("OLYMP\0") || startsWith("EPSON") || startsWith("AGFA")) {
			// Epson and Agfa use the Olympus layout
			return tiffBaseKnown && readIFD(MakerNoteDirectory.OLYMPUS, 8, tiffBase());
		} else if(startsWith("OLYMPUS\0II") || startsWith("OLYMPUS\0MM")) {
			// Offsets relative to the start of the maker note
			bigEndian = (data[8] == 'M');
			return readIFD(MakerNoteDirectory.OLYMPUS, 12, 0);
		} else if(startsWith("OM SYSTEM\0\0\0II") || startsWith("OM SYSTEM\0\0\0MM")) {
			bigEndian = (data[12] == 'M');
			return readIFD(MakerNoteDirectory.OLYMPUS, 14, 0);
		} else if(upperMake.startsWith("MINOLTA")) {
			// Seen with an Olympus layout that starts immediately
			return tiffBaseKnown && readIFD(MakerNoteDirectory.OLYMPUS, 0, tiffBase());
		} else if(upperMake.startsWith("NIKON")) {
			if(startsWith("Nikon")) {
				switch(u8(6)) {
					case 1:
						return tiffBaseKnown && readIFD(MakerNoteDirectory.NIKON_TYPE1, 8, tiffBase());
					case 2:
						// "Nikon\0\2\0\0\0" followed by an embedded TIFF header at offset 10
						if(data.length < 18) return false;
						bigEndian = (data[10] == 'M');
						return readIFD(MakerNoteDirectory.NIKON_TYPE2, 18, 10);
					default:
						return false;
				}
			}
			// The IFD begins with the first byte (Coolpix 775, E990, D1)
			return tiffBaseKnown && readIFD(MakerNoteDirectory.NIKON_TYPE2, 0, tiffBase());
		} else if(startsWith("SONY CAM") || startsWith("SONY DSC")) {
			return tiffBaseKnown && readIFD(MakerNoteDirectory.SONY_TYPE1, 12, tiffBase());
		} else if(make != null && make.startsWith("SONY") && !(u8(0) == 0x01 && u8(1) == 0x00)) {
			// IFD begins with the first byte, used in SR2 and ARW images
			return tiffBaseKnown && readIFD(MakerNoteDirectory.SONY_TYPE1, 0, tiffBase());
		} else if(startsWith("SEMC MS\0\0\0\0\0")) {
			// Always Motorola; skip the 12 byte header, "MM" and 6 more bytes
			bigEndian = true;
			return tiffBaseKnown && readIFD(MakerNoteDirectory.SONY_TYPE6, 20, tiffBase());
		} else if(startsWith("SIGMA\0\0\0") || startsWith("FOVEON\0\0")) {
			return tiffBaseKnown && readIFD(MakerNoteDirectory.SIGMA, 10, tiffBase());
		} else if(startsWith("KDK")) {
			// Kodak uses a fixed binary layout, not an IFD; not supported
			return false;
		} else if("CANON".equals(upperMake)) {
			return tiffBaseKnown && readIFD(MakerNoteDirectory.CANON, 0, tiffBase());
		} else if(upperMake.startsWith("CASIO")) {
			if(startsWith("QVC\0\0\0"))
				return tiffBaseKnown && readIFD(MakerNoteDirectory.CASIO_TYPE2, 6, tiffBase());
			return tiffBaseKnown && readIFD(MakerNoteDirectory.CASIO_TYPE1, 0, tiffBase());
		} else if(startsWith("FUJIFILM") || "FUJIFILM".equals(upperMake)) {
			// Always Intel. The 4 bytes after "FUJIFILM" point to the IFD, relative to the maker
			// note, which is also the base for all value offsets. Leica Digilux 4.3 uses this too.
			bigEndian = false;
			if(data.length < 12) return false;
			return readIFD(MakerNoteDirectory.FUJIFILM, s32(8), 0);
		} else if(startsWith("KYOCERA")) {
			return tiffBaseKnown && readIFD(MakerNoteDirectory.KYOCERA, 22, tiffBase());
		} else if(startsWith("LEICA")) {
			bigEndian = false;
			// "LEICA\0\x01\0" (X1), \x04 (X VARIO), \x05 (X2), \x06 (T), \x07 (X)
			if(data.length >= 8 && data[5] == 0 && data[7] == 0 && (data[6] == 1 || (data[6] >= 4 && data[6] <= 7)))
				return readIFD(MakerNoteDirectory.LEICA_TYPE5, 8, 0);
			if("Leica Camera AG".equals(make))
				return tiffBaseKnown && readIFD(MakerNoteDirectory.LEICA, 8, tiffBase());
			if("LEICA".equals(make)) // Some Leica cameras use the Panasonic layout
				return tiffBaseKnown && readIFD(MakerNoteDirectory.PANASONIC, 8, tiffBase());
			return false;
		} else if(startsWith("Panasonic\0\0\0")) {
			// No next-IFD pointer; offsets relative to the TIFF header
			return tiffBaseKnown && readIFD(MakerNoteDirectory.PANASONIC, 12, tiffBase());
		} else if(startsWith("AOC\0")) {
			// Casio type 2 tags, offsets relative to the maker note (Pentax *ist D)
			return readIFD(MakerNoteDirectory.CASIO_TYPE2, 6, 0);
		} else if(upperMake.startsWith("PENTAX") || upperMake.startsWith("ASAHI")) {
			// Offsets relative to the maker note (Optio 330, 430)
			return readIFD(MakerNoteDirectory.PENTAX, 0, 0);
		} else if(startsWith("SANYO\0\1\0")) {
			return readIFD(MakerNoteDirectory.SANYO, 8, 0);
		} else if(upperMake.startsWith("RICOH")) {
			// "Rv"/"Rev" maker notes are a text format, not supported
			if(startsWith("Rv") || startsWith("Rev")) return false;
			if(data.length >= 5 && head(5).equalsIgnoreCase("Ricoh")) {
				bigEndian = true;
				return readIFD(MakerNoteDirectory.RICOH, 8, 0);
			}
			return false;
		} else if(startsWith("Apple iOS\0")) {
			bigEndian = true;
			return readIFD(MakerNoteDirectory.APPLE, 14, 0);
		} else if(data.length >= 2 && u16(0) == 61697) {
			// Reconyx HyperFire uses a fixed binary layout; not supported
			return false;
		} else if(startsWith("RECONYXUF") || startsWith("RECONYXH2")) {
			return false;
		} else if("SAMSUNG".equals(upperMake)) {
			return tiffBaseKnown && readIFD(MakerNoteDirectory.SAMSUNG_TYPE2, 0, tiffBase());
		} else if("DJI".equals(upperMake)) {
			return tiffBaseKnown && readIFD(MakerNoteDirectory.DJI, 0, tiffBase());
		}

		return false;
	}

	private boolean readIFD(MakerNoteDirectory directory, int ifdOffset, int base) {
		MakerNoteIFD ifd = new MakerNoteIFD(directory);
		makerNote.addDirectory(ifd);
		readIFD(ifd, ifdOffset, base, 0);
		return !ifd.getEntries().isEmpty() || makerNote.getDirectories().size() > 1;
	}

	private void readIFD(MakerNoteIFD ifd, int ifdOffset, int base, int depth) {
		if(depth > MAX_DEPTH || !visitedIFDs.add(ifdOffset)) return;
		if(ifdOffset < 0 || ifdOffset > data.length - 2) return;

		boolean savedEndian = bigEndian;
		int count = u16(ifdOffset);
		// Some software rewrites the byte order of the file but leaves maker notes alone. No real IFD
		// has more than 255 entries, so a count like 0x0500 means the byte order is swapped here.
		if(count > 0xff && (count & 0xff) == 0) {
			bigEndian = !bigEndian;
			count >>= 8;
		}
		// Unlike the main IFDs, several vendors omit the next-IFD pointer, so don't require it
		if(count > (data.length - ifdOffset - 2) / 12) {
			bigEndian = savedEndian;
			return;
		}

		int invalidFormats = 0;
		for(int i = 0; i < count && entryCount < MAX_ENTRIES; i++) {
			int entryOffset = ifdOffset + 2 + 12 * i;
			int tag = u16(entryOffset);
			int format = u16(entryOffset + 2);
			long components = u32(entryOffset + 4);
			int componentSize = componentSize(format);
			if(componentSize == 0) {
				// An invalid type usually means we are reading garbage; give up after a few
				if(++invalidFormats > 5) break;
				continue;
			}
			long byteCount = components * componentSize;
			if(components > Integer.MAX_VALUE || byteCount > data.length) continue;

			long valueOffset = (byteCount > 4) ? (base + u32(entryOffset + 8)) : (entryOffset + 8);
			if(valueOffset < 0 || valueOffset + byteCount > data.length) continue; // Points outside the maker note

			boolean tiffRelative = (makerNoteOffset > 0 && base == tiffBase());
			if(ifd.getDirectory() == MakerNoteDirectory.OLYMPUS && olympusSubDirectory(tag) != null) {
				MakerNoteIFD sub = new MakerNoteIFD(olympusSubDirectory(tag));
				makerNote.addDirectory(sub);
				if(byteCount == 4 * components && components > 0) {
					// IFD pointer(s)
					if(byteCount > 4 && tiffRelative) patchOffset(entryOffset + 8);
					for(int j = 0; j < components; j++) {
						int pointer = (int)valueOffset + 4 * j;
						int subIFD = (int)(base + u32(pointer));
						if(tiffRelative) patchOffset(pointer);
						readIFD(sub, subIFD, base, depth + 1);
					}
				} else {
					// The sub IFD begins immediately at the value
					if(byteCount > 4 && tiffRelative) patchOffset(entryOffset + 8);
					readIFD(sub, (int)valueOffset, base, depth + 1);
				}
				continue;
			}
			
			if(relocated != null) {
				if(byteCount > 4 && tiffRelative) patchOffset(entryOffset + 8);
			} else {
				addEntry(ifd, tag, format, (int)components, (int)valueOffset);
			}
		}
		bigEndian = savedEndian;
	}

	// Shifts the TIFF header relative offset stored at the given position, in relocation mode
	private void patchOffset(int offset) {
		if(relocated == null) return;
		int value = (int)(u32(offset) + relocationDelta);
		if(bigEndian) {
			relocated[offset] = (byte)(value >>> 24);
			relocated[offset + 1] = (byte)(value >>> 16);
			relocated[offset + 2] = (byte)(value >>> 8);
			relocated[offset + 3] = (byte)value;
		} else {
			relocated[offset] = (byte)value;
			relocated[offset + 1] = (byte)(value >>> 8);
			relocated[offset + 2] = (byte)(value >>> 16);
			relocated[offset + 3] = (byte)(value >>> 24);
		}
	}
	
	private static MakerNoteDirectory olympusSubDirectory(int tag) {
		switch(tag) {
			case 0x2010: return MakerNoteDirectory.OLYMPUS_EQUIPMENT;
			case 0x2020: return MakerNoteDirectory.OLYMPUS_CAMERA_SETTINGS;
			case 0x2030: return MakerNoteDirectory.OLYMPUS_RAW_DEVELOPMENT;
			case 0x2031: return MakerNoteDirectory.OLYMPUS_RAW_DEVELOPMENT_2;
			case 0x2040: return MakerNoteDirectory.OLYMPUS_IMAGE_PROCESSING;
			case 0x2050: return MakerNoteDirectory.OLYMPUS_FOCUS_INFO;
			case 0x3000: return MakerNoteDirectory.OLYMPUS_RAW_INFO;
			case 0x4000: return MakerNoteDirectory.OLYMPUS;
			default: return null;
		}
	}

	private void addEntry(MakerNoteIFD ifd, int tag, int format, int count, int offset) {
		FieldType type = FieldType.fromShort((short)format);
		Object value;
		switch(type) {
			case ASCII:
				int end = offset;
				while(end < offset + count && data[end] != 0) end++;
				value = new String(data, offset, end - offset, StandardCharsets.UTF_8);
				break;
			case BYTE:
			case SBYTE:
			case UNDEFINED:
				byte[] bytes = new byte[count];
				System.arraycopy(data, offset, bytes, 0, count);
				value = bytes;
				break;
			case SHORT:
			case SSHORT:
				short[] shorts = new short[count];
				for(int i = 0; i < count; i++) shorts[i] = (short)u16(offset + 2 * i);
				value = shorts;
				break;
			case LONG:
			case SLONG:
			case IFD:
				int[] ints = new int[count];
				for(int i = 0; i < count; i++) ints[i] = s32(offset + 4 * i);
				value = ints;
				break;
			case RATIONAL:
			case SRATIONAL:
				int[] rationals = new int[2 * count];
				for(int i = 0; i < 2 * count; i++) rationals[i] = s32(offset + 4 * i);
				value = rationals;
				break;
			case FLOAT:
				float[] floats = new float[count];
				for(int i = 0; i < count; i++) floats[i] = Float.intBitsToFloat(s32(offset + 4 * i));
				value = floats;
				break;
			case DOUBLE:
				double[] doubles = new double[count];
				for(int i = 0; i < count; i++) doubles[i] = Double.longBitsToDouble(s64(offset + 8 * i));
				value = doubles;
				break;
			default:
				return;
		}

		if(ifd.getDirectory() == MakerNoteDirectory.OLYMPUS && type == FieldType.UNDEFINED
				&& (tag == OLYMPUS_CAMERA_SETTINGS_1 || tag == OLYMPUS_CAMERA_SETTINGS_2)) {
			// Always Motorola order, whatever the byte order of the maker note
			byte[] settings = (byte[])value;
			for(int i = 0; i + 4 <= settings.length && entryCount < MAX_ENTRIES; i += 4) {
				int setting = (settings[i] & 0xff) << 24 | (settings[i + 1] & 0xff) << 16 | (settings[i + 2] & 0xff) << 8 | (settings[i + 3] & 0xff);
				ifd.addEntry(newEntry(ifd, OLYMPUS_CAMERA_SETTINGS_BASE + i / 4, FieldType.SLONG, 1, new int[] {setting}));
			}
			return;
		}
		
		if(ifd.getDirectory() == MakerNoteDirectory.CANON && count > 1 && (type == FieldType.SHORT || type == FieldType.SLONG)) {
			if(tag == CANON_AF_INFO_ARRAY && type == FieldType.SHORT) {
				addCanonAFInfo(ifd, (short[])value);
				return;
			}
			int subBase = canonArrayBase(tag);
			if(subBase != 0) {
				for(int i = 0; i < count && entryCount < MAX_ENTRIES; i++) {
					Object element = (type == FieldType.SHORT) ? new short[] {((short[])value)[i]} : new int[] {((int[])value)[i]};
					ifd.addEntry(newEntry(ifd, subBase + i, type, 1, element));
				}
				return;
			}
		}

		ifd.addEntry(newEntry(ifd, tag, type, count, value));
	}

	/**
	 * Splits the Canon AFInfo array into synthetic tags. Values are sequential and some have a
	 * variable length depending on the number of AF points held in the first element (layout as
	 * documented by ExifTool's Canon.pm and implemented by metadata-extractor).
	 */
	private void addCanonAFInfo(MakerNoteIFD ifd, short[] values) {
		int numAFPoints = values[0] & 0xffff;
		int tag = CANON_AF_INFO_BASE;
		for(int i = 0; i < values.length && entryCount < MAX_ENTRIES; i++, tag++) {
			if(tag == CANON_AF_AREA_X_POSITIONS || tag == CANON_AF_AREA_Y_POSITIONS) {
				// numAFPoints signed values; skip silently if the array is too short
				if(values.length - 1 >= i + numAFPoints)
					ifd.addEntry(newEntry(ifd, tag, FieldType.SSHORT, numAFPoints, Arrays.copyOfRange(values, i, i + numAFPoints)));
				i += numAFPoints - 1;
			} else if(tag == CANON_AF_POINTS_IN_FOCUS) {
				// A bit mask, 16 AF points per value
				int length = (numAFPoints + 15) / 16;
				if(values.length - 1 >= i + length)
					ifd.addEntry(newEntry(ifd, tag, FieldType.SHORT, length, Arrays.copyOfRange(values, i, i + length)));
				i += length - 1;
			} else {
				ifd.addEntry(newEntry(ifd, tag, FieldType.SHORT, 1, new short[] {values[i]}));
			}
		}
	}
	
	private MakerNoteEntry newEntry(MakerNoteIFD ifd, int tag, FieldType type, int count, Object value) {
		entryCount++;
		String name = ifd.getDirectory().getTagName(tag);
		if(name == null) name = String.format("Unknown tag (0x%04x)", tag);
		return new MakerNoteEntry(tag, name, type, count, value);
	}

	private static int canonArrayBase(int tag) {
		switch(tag) {
			case CANON_CAMERA_SETTINGS_ARRAY: return 0xC100;
			case CANON_FOCAL_LENGTH_ARRAY: return 0xC200;
			case CANON_SHOT_INFO_ARRAY: return 0xC400;
			case CANON_PANORAMA_ARRAY: return 0xC500;
			default: return 0;
		}
	}

	private static int componentSize(int format) {
		switch(format) {
			case 1: case 2: case 6: case 7: return 1; // BYTE, ASCII, SBYTE, UNDEFINED
			case 3: case 8: return 2; // SHORT, SSHORT
			case 4: case 9: case 11: case 13: return 4; // LONG, SLONG, FLOAT, IFD
			case 5: case 10: case 12: return 8; // RATIONAL, SRATIONAL, DOUBLE
			default: return 0;
		}
	}

	private boolean startsWith(String signature) {
		return data.length >= signature.length() && head(signature.length()).equals(signature);
	}

	private String head(int length) {
		return new String(data, 0, length, StandardCharsets.ISO_8859_1);
	}

	// Readers below are only called with offsets validated against data.length

	private int u8(int offset) {
		return (offset < data.length) ? (data[offset] & 0xff) : -1;
	}

	private int u16(int offset) {
		int b0 = data[offset] & 0xff, b1 = data[offset + 1] & 0xff;
		return bigEndian ? (b0 << 8 | b1) : (b1 << 8 | b0);
	}

	private int s32(int offset) {
		int b0 = data[offset] & 0xff, b1 = data[offset + 1] & 0xff, b2 = data[offset + 2] & 0xff, b3 = data[offset + 3] & 0xff;
		return bigEndian ? (b0 << 24 | b1 << 16 | b2 << 8 | b3) : (b3 << 24 | b2 << 16 | b1 << 8 | b0);
	}

	private long u32(int offset) {
		return s32(offset) & 0xffffffffL;
	}

	private long s64(int offset) {
		long high = u32(offset), low = u32(offset + 4);
		return bigEndian ? (high << 32 | low) : (low << 32 | high);
	}
}
