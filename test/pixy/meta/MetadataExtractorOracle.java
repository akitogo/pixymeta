package pixy.meta;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import com.drew.imaging.ImageMetadataReader;
import com.drew.imaging.ImageProcessingException;
import com.drew.lang.Rational;
import com.drew.metadata.Directory;
import com.drew.metadata.StringValue;
import com.drew.metadata.Tag;
import com.drew.metadata.exif.ExifIFD0Directory;

import pixy.image.tiff.FieldType;
import pixy.image.tiff.TiffField;
import pixy.image.tiff.TiffTag;
import pixy.meta.exif.Exif;
import pixy.meta.exif.makernote.MakerNote;
import pixy.meta.exif.makernote.MakerNoteEntry;
import pixy.meta.exif.makernote.MakerNoteIFD;

/**
 * Helpers that use metadata-extractor (test scope only) as an independent reference parser:
 * whatever PIXYMETA writes must be readable by it, and what PIXYMETA reads should agree with it.
 */
final class MetadataExtractorOracle {

	/** Maker notes in fixed binary layouts that PIXYMETA deliberately does not decode. */
	private static final List<String> UNSUPPORTED_MAKER_NOTES = List.of("Kodak Makernote",
			"Reconyx HyperFire Makernote", "Reconyx HyperFire 2 Makernote", "Reconyx UltraFire Makernote");

	private MetadataExtractorOracle() {}

	static com.drew.metadata.Metadata read(byte[] image) throws IOException {
		try {
			return ImageMetadataReader.readMetadata(new ByteArrayInputStream(image));
		} catch (ImageProcessingException e) {
			throw new IOException("metadata-extractor could not read the image", e);
		}
	}

	/** Returns the first directory with the given name, e.g. "Exif IFD0" or "Canon Makernote". */
	static Directory directory(com.drew.metadata.Metadata metadata, String name) {
		for (Directory directory : metadata.getDirectories()) {
			if (directory.getName().equals(name))
				return directory;
		}
		return null;
	}

	/** All errors metadata-extractor recorded while reading, prefixed with their directory. */
	static List<String> errors(com.drew.metadata.Metadata metadata) {
		List<String> errors = new ArrayList<>();
		for (Directory directory : metadata.getDirectories()) {
			for (String error : directory.getErrors())
				errors.add(directory.getName() + ": " + error);
		}
		return errors;
	}

	/**
	 * Compares what both libraries read from one image. Returns human readable differences; an
	 * empty list means they agree.
	 */
	static List<String> compareRead(Map<MetadataType, Metadata> pixy, com.drew.metadata.Metadata me) {
		List<String> diffs = new ArrayList<>();
		Exif exif = (Exif) pixy.get(MetadataType.EXIF);
		ExifIFD0Directory ifd0 = me.getFirstDirectoryOfType(ExifIFD0Directory.class);
		if (exif != null && exif.getImageIFD() != null && ifd0 != null) {
			compareAscii(diffs, "Make", exif, TiffTag.MAKE, ifd0.getString(ExifIFD0Directory.TAG_MAKE));
			compareAscii(diffs, "Model", exif, TiffTag.MODEL, ifd0.getString(ExifIFD0Directory.TAG_MODEL));
			compareAscii(diffs, "DateTime", exif, TiffTag.DATETIME, ifd0.getString(ExifIFD0Directory.TAG_DATETIME));
		}
		diffs.addAll(compareMakerNote(exif == null ? null : exif.getMakerNote(), me));
		return diffs;
	}

	private static void compareAscii(List<String> diffs, String label, Exif exif, TiffTag tag, String expected) {
		TiffField<?> field = exif.getImageIFD().getField(tag);
		// metadata-extractor reads up to the first NUL; PIXYMETA keeps all NUL separated strings
		String actual = (field == null) ? null : String.valueOf(field.getData()).split("\0", -1)[0];
		if (!normalize(expected).equals(normalize(actual)))
			diffs.add(label + ": pixymeta '" + actual + "' vs metadata-extractor '" + expected + "'");
	}

	/**
	 * Every maker note tag metadata-extractor decodes must be decoded by PIXYMETA too, with the
	 * same name and value. Sub directories metadata-extractor derives from binary blobs (e.g. Nikon
	 * Picture Control) are not compared; PIXYMETA leaves those as raw values of their parent tag.
	 */
	static List<String> compareMakerNote(MakerNote makerNote, com.drew.metadata.Metadata me) {
		List<String> diffs = new ArrayList<>();
		for (Directory directory : me.getDirectories()) {
			String name = directory.getName();
			boolean isMakerNote = name.endsWith("Makernote") || (name.startsWith("Olympus") && directory.getParent() != null);
			if (!isMakerNote || directory.getTagCount() == 0 || UNSUPPORTED_MAKER_NOTES.contains(name))
				continue;
			MakerNoteIFD ifd = findDirectory(makerNote, name);
			if (ifd == null) {
				diffs.add(name + ": not decoded by pixymeta (" + directory.getTagCount() + " tags)");
				continue;
			}
			for (Tag tag : directory.getTags()) {
				MakerNoteEntry entry = ifd.getEntry(tag.getTagType());
				String id = name + " 0x" + Integer.toHexString(tag.getTagType());
				if (entry == null) {
					diffs.add(id + " (" + tag.getTagName() + "): missing");
					continue;
				}
				// The release jar can lag the tag tables PIXYMETA generated from newer sources
				if (!tag.getTagName().startsWith("Unknown tag") && !tag.getTagName().equals(entry.getName()))
					diffs.add(id + ": name '" + entry.getName() + "' vs '" + tag.getTagName() + "'");
				String valueDiff = compareValue(directory.getObject(tag.getTagType()), entry);
				if (valueDiff != null)
					diffs.add(id + " (" + tag.getTagName() + "): " + valueDiff);
			}
		}
		return diffs;
	}

	private static MakerNoteIFD findDirectory(MakerNote makerNote, String name) {
		if (makerNote == null)
			return null;
		for (MakerNoteIFD ifd : makerNote.getDirectories()) {
			if (ifd.getName().equals(name) && !ifd.getEntries().isEmpty())
				return ifd;
		}
		return null;
	}

	/** Returns null if equal, otherwise a description of the difference. */
	private static String compareValue(Object expected, MakerNoteEntry entry) {
		Object actual = entry.getValue();
		if (expected instanceof StringValue || expected instanceof String) {
			if (entry.getType() != FieldType.ASCII)
				return null; // metadata-extractor sometimes stores decoded text for binary tags
			return normalize(expected.toString()).equals(normalize((String) actual)) ? null
					: "'" + actual + "' vs '" + expected + "'";
		}
		if (expected instanceof byte[] && actual instanceof byte[]) {
			return Arrays.equals((byte[]) expected, (byte[]) actual) ? null : "byte arrays differ";
		}
		long[] expectedLongs = toLongs(expected);
		long[] actualLongs = entry.getValueAsLongs();
		if (expectedLongs != null && actualLongs != null) {
			return Arrays.equals(expectedLongs, actualLongs) ? null
					: Arrays.toString(actualLongs) + " vs " + Arrays.toString(expectedLongs);
		}
		if (expected instanceof Rational || expected instanceof Rational[]) {
			Rational[] rationals = (expected instanceof Rational) ? new Rational[] {(Rational) expected} : (Rational[]) expected;
			int[] pairs = (int[]) actual;
			for (int i = 0; i < rationals.length; i++) {
				if ((int) rationals[i].getNumerator() != pairs[2 * i] || (int) rationals[i].getDenominator() != pairs[2 * i + 1])
					return "rational " + i + " differs";
			}
		}
		return null; // Other representations (floats, doubles) are not compared
	}

	private static long[] toLongs(Object value) {
		if (value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte)
			return new long[] {((Number) value).longValue()};
		if (value instanceof int[])
			return Arrays.stream((int[]) value).asLongStream().toArray();
		if (value instanceof long[])
			return (long[]) value;
		if (value instanceof short[]) {
			short[] shorts = (short[]) value;
			long[] longs = new long[shorts.length];
			for (int i = 0; i < shorts.length; i++)
				longs[i] = shorts[i];
			return longs;
		}
		return null;
	}

	private static String normalize(String s) {
		return (s == null) ? "" : s.replace("\0", " ").trim();
	}
}
