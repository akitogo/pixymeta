package pixy.meta;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static pixy.meta.CrossValidationTest.read;
import static pixy.meta.CrossValidationTest.transform;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.drew.lang.Rational;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.drew.metadata.exif.ExifSubIFDDirectory;
import com.drew.metadata.exif.GpsDirectory;

import pixy.image.tiff.FieldType;
import pixy.image.tiff.Tag;
import pixy.image.tiff.TiffField;
import pixy.image.tiff.TiffTag;
import pixy.meta.exif.Exif;
import pixy.meta.exif.ExifTag;
import pixy.meta.exif.GPSTag;
import pixy.meta.exif.InteropTag;
import pixy.meta.jpeg.JpegExif;
import pixy.meta.tiff.TiffExif;

/**
 * The field type table on the tag enums and the value coercion behind {@code Exif.addField}:
 * callers give plain Java values and the library picks the TIFF type and array representation.
 */
public class FieldCoercionTest {

	// ---- field type table ----

	@Test
	@DisplayName("Every EXIF and GPS tag except the non-standard ones has a field type")
	void fieldTypesDefined() {
		List<ExifTag> untyped = List.of(ExifTag.UNKNOWN, ExifTag.EXPAND_SOFTWARE, ExifTag.EXPAND_LENS, ExifTag.EXPAND_FILM,
				ExifTag.EXPAND_FILTER_LENS, ExifTag.EXPAND_SCANNER, ExifTag.EXPAND_FLASH_LAMP);
		for (ExifTag tag : ExifTag.values())
			assertEquals(untyped.contains(tag), tag.getFieldType() == FieldType.UNKNOWN, tag.name());
		for (GPSTag tag : GPSTag.values())
			assertEquals(tag == GPSTag.UNKNOWN, tag.getFieldType() == FieldType.UNKNOWN, tag.name());
		for (InteropTag tag : InteropTag.values())
			assertEquals(tag == InteropTag.UNKNOWN, tag.getFieldType() == FieldType.UNKNOWN, tag.name());
		// Spot checks against the EXIF 2.32 specification
		assertEquals(FieldType.RATIONAL, ExifTag.FNUMBER.getFieldType());
		assertEquals(FieldType.SRATIONAL, ExifTag.EXPOSURE_BIAS_VALUE.getFieldType());
		assertEquals(FieldType.SHORT, ExifTag.ISO_SPEED_RATINGS.getFieldType());
		assertEquals(FieldType.ASCII, ExifTag.DATE_TIME_ORIGINAL.getFieldType());
		assertEquals(FieldType.UNDEFINED, ExifTag.USER_COMMENT.getFieldType());
		assertEquals(FieldType.BYTE, GPSTag.GPS_VERSION_ID.getFieldType());
		assertEquals(FieldType.RATIONAL, GPSTag.GPS_LATITUDE.getFieldType());
		assertEquals(FieldType.SHORT, TiffTag.ORIENTATION.getFieldType());
		assertEquals(FieldType.ASCII, TiffTag.HOST_COMPUTER.getFieldType());
		assertEquals(FieldType.WINDOWSXP, TiffTag.WINDOWS_XP_TITLE.getFieldType());
	}

	@Test
	@DisplayName("TIFF tags that previously had a type still have the same one")
	void tiffTagTypesUnchanged() {
		assertEquals(FieldType.LONG, TiffTag.IMAGE_WIDTH.getFieldType());
		assertEquals(FieldType.SHORT, TiffTag.COMPRESSION.getFieldType());
		assertEquals(FieldType.RATIONAL, TiffTag.X_RESOLUTION.getFieldType());
		assertEquals(FieldType.ASCII, TiffTag.ARTIST.getFieldType());
		assertEquals(FieldType.UNDEFINED, TiffTag.IPTC.getFieldType());
		assertEquals(FieldType.LONG, TiffTag.EXIF_SUB_IFD.getFieldType());
	}

	// ---- rational approximation ----

	@Test
	@DisplayName("Doubles become the expected rationals")
	void rationals() {
		assertArrayEquals(new long[] {14, 5}, FieldType.toRational(2.8, false));
		assertArrayEquals(new long[] {1, 250}, FieldType.toRational(0.004, false));
		assertArrayEquals(new long[] {1, 3}, FieldType.toRational(1.0 / 3, false));
		assertArrayEquals(new long[] {100, 1}, FieldType.toRational(100, false));
		assertArrayEquals(new long[] {0, 1}, FieldType.toRational(0, false));
		assertArrayEquals(new long[] {-7, 3}, FieldType.toRational(-7.0 / 3, true));
		long[] pi = FieldType.toRational(Math.PI, false);
		assertEquals(Math.PI, (double) pi[0] / pi[1], 1e-9);
		assertThrows(IllegalArgumentException.class, () -> FieldType.toRational(-1.5, false));
		assertThrows(IllegalArgumentException.class, () -> FieldType.toRational(Double.NaN, true));
	}

	// ---- coercion per type ----

	@Test
	@DisplayName("Numbers, strings and lists are coerced to the TIFF array types")
	void coerceIntegers() {
		assertArrayEquals(new short[] {400}, (short[]) FieldType.SHORT.coerce(400));
		assertArrayEquals(new short[] {400}, (short[]) FieldType.SHORT.coerce("400"));
		assertArrayEquals(new short[] {1, 2, 3}, (short[]) FieldType.SHORT.coerce(List.of(1, 2, 3)));
		assertArrayEquals(new short[] {1, 2, 3}, (short[]) FieldType.SHORT.coerce("1, 2 3"));
		assertArrayEquals(new short[] {(short) 65535}, (short[]) FieldType.SHORT.coerce(65535));
		assertArrayEquals(new int[] {70000}, (int[]) FieldType.LONG.coerce(70000L));
		assertArrayEquals(new int[] {-5}, (int[]) FieldType.SLONG.coerce(-5));
		assertArrayEquals(new byte[] {2, 3, 0, 0}, (byte[]) FieldType.BYTE.coerce(List.of(2, 3, 0, 0)));
		assertArrayEquals(new byte[] {1}, (byte[]) FieldType.BYTE.coerce(1));
		assertArrayEquals(new byte[] {'0', '2', '3', '2'}, (byte[]) FieldType.UNDEFINED.coerce("0232"));
		short[] same = {1};
		assertTrue(same == FieldType.SHORT.coerce(same), "arrays of the right type pass through");
		assertThrows(IllegalArgumentException.class, () -> FieldType.SHORT.coerce(65536));
		assertThrows(IllegalArgumentException.class, () -> FieldType.SHORT.coerce(-1));
		assertThrows(IllegalArgumentException.class, () -> FieldType.SHORT.coerce(2.5));
		assertThrows(IllegalArgumentException.class, () -> FieldType.SHORT.coerce("abc"));
		assertThrows(IllegalArgumentException.class, () -> FieldType.SHORT.coerce(new Object()));
	}

	@Test
	@DisplayName("Rational inputs: pairs, numbers, decimal and fraction strings, per-element lists")
	void coerceRationals() {
		assertArrayEquals(new int[] {14, 5}, (int[]) FieldType.RATIONAL.coerce(2.8));
		assertArrayEquals(new int[] {28, 10}, (int[]) FieldType.RATIONAL.coerce("28/10"));
		assertArrayEquals(new int[] {1, 250}, (int[]) FieldType.RATIONAL.coerce("1/250"));
		assertArrayEquals(new int[] {50, 1}, (int[]) FieldType.RATIONAL.coerce(50));
		assertArrayEquals(new int[] {52, 1, 30, 1, 25, 2}, (int[]) FieldType.RATIONAL.coerce(new double[] {52, 30, 12.5}));
		assertArrayEquals(new int[] {52, 1, 30, 1, 25, 2}, (int[]) FieldType.RATIONAL.coerce("52 30 12.5"));
		assertArrayEquals(new int[] {52, 1, 30, 1, 25, 2}, (int[]) FieldType.RATIONAL.coerce(List.of(52, 30, 12.5)));
		int[] pairs = {1, 2, 3, 4};
		assertTrue(pairs == FieldType.RATIONAL.coerce(pairs), "int[] pairs pass through");
		assertArrayEquals(new int[] {-1, 3}, (int[]) FieldType.SRATIONAL.coerce(-1.0 / 3));
		assertThrows(IllegalArgumentException.class, () -> FieldType.RATIONAL.coerce(new int[] {1, 2, 3}));
		assertThrows(IllegalArgumentException.class, () -> FieldType.RATIONAL.coerce(-2.8));
	}

	@Test
	@DisplayName("Dates are formatted as EXIF date-times for ASCII fields")
	void coerceDates() {
		Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
		cal.clear();
		cal.set(2026, Calendar.NOVEMBER, 9, 13, 5, 7);
		assertEquals("2026:11:09 13:05:07", FieldType.ASCII.coerce(cal));
		assertEquals("2026:11:09 13:05:07", FieldType.ASCII.coerce(LocalDateTime.of(2026, 11, 9, 13, 5, 7)));
		assertEquals("plain", FieldType.ASCII.coerce("plain"));
		assertEquals("42", FieldType.ASCII.coerce(42));
		assertThrows(IllegalArgumentException.class, () -> FieldType.ASCII.coerce(java.time.LocalDate.of(2026, 11, 9)));
	}

	@Test
	@DisplayName("Floats and doubles")
	void coerceFloating() {
		assertArrayEquals(new float[] {1.5f}, (float[]) FieldType.FLOAT.coerce(1.5));
		assertArrayEquals(new double[] {1.5, 2.5}, (double[]) FieldType.DOUBLE.coerce("1.5 2.5"));
		TiffField<?> f = FieldType.createField(TiffTag.UNKNOWN, FieldType.DOUBLE, new double[] {1.5});
		assertNotNull(f, "DOUBLE fields can now be created");
		assertEquals(FieldType.DOUBLE, f.getType());
	}

	@Test
	@DisplayName("createField(tag, value) uses the tag's type; unknown types are rejected")
	void createFieldFromTag() {
		TiffField<?> f = FieldType.createField(ExifTag.FNUMBER, 2.8);
		assertEquals(FieldType.RATIONAL, f.getType());
		assertArrayEquals(new int[] {14, 5}, (int[]) f.getData());
		assertEquals(FieldType.ASCII, FieldType.createField(TiffTag.ARTIST, "x").getType());
		assertThrows(IllegalArgumentException.class, () -> FieldType.createField(ExifTag.EXPAND_LENS, "x"));
		assertThrows(IllegalArgumentException.class, () -> FieldType.createField(ExifTag.FNUMBER, "not a number"));
	}

	// ---- Exif.addField end to end ----

	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = {"images/1.jpg", "images/f1.tif"})
	@DisplayName("Fields added with plain values read back correctly in metadata-extractor")
	void addFieldRoundTrip(String image) throws Exception {
		Exif exif = image.endsWith(".tif") ? new TiffExif() : new JpegExif();
		exif.addField(TiffTag.ARTIST, "Jane Tester");
		exif.addField(TiffTag.ORIENTATION, 6);
		exif.addField(ExifTag.DATE_TIME_ORIGINAL, LocalDateTime.of(2026, 11, 9, 13, 5, 7));
		exif.addField(ExifTag.ISO_SPEED_RATINGS, 400);
		exif.addField(ExifTag.FNUMBER, 2.8);
		exif.addField(ExifTag.EXPOSURE_TIME, "1/250");
		exif.addField(ExifTag.EXPOSURE_BIAS_VALUE, -1.0 / 3);
		exif.addField(ExifTag.EXIF_VERSION, "0232");
		exif.addField(ExifTag.USER_COMMENT, "Hello");
		exif.addField(GPSTag.GPS_VERSION_ID, List.of(2, 3, 0, 0));
		exif.addField(GPSTag.GPS_LATITUDE_REF, "N");
		exif.addField(GPSTag.GPS_LATITUDE, new double[] {52, 30, 12.5});
		exif.addField(GPSTag.GPS_LONGITUDE_REF, "E");
		exif.addField(GPSTag.GPS_LONGITUDE, "13 24 36");
		exif.addField(GPSTag.GPS_ALTITUDE_REF, 0);
		exif.addField(GPSTag.GPS_ALTITUDE, 34.5);
		exif.addField(GPSTag.GPS_PROCESSING_METHOD, "GPS");

		byte[] out = transform(read(image), (is, os) -> Metadata.insertExif(is, os, exif, true));

		com.drew.metadata.Metadata me = MetadataExtractorOracle.read(out);
		ExifIFD0Directory ifd0 = me.getFirstDirectoryOfType(ExifIFD0Directory.class);
		ExifSubIFDDirectory sub = me.getFirstDirectoryOfType(ExifSubIFDDirectory.class);
		GpsDirectory gps = me.getFirstDirectoryOfType(GpsDirectory.class);
		assertEquals("Jane Tester", ifd0.getString(ExifIFD0Directory.TAG_ARTIST));
		assertEquals(6, ifd0.getInt(ExifIFD0Directory.TAG_ORIENTATION));
		assertEquals("2026:11:09 13:05:07", sub.getString(ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL));
		assertEquals(400, sub.getInt(ExifSubIFDDirectory.TAG_ISO_EQUIVALENT));
		assertEquals(2.8, sub.getDouble(ExifSubIFDDirectory.TAG_FNUMBER), 1e-9);
		assertEquals(new Rational(1, 250), sub.getRational(ExifSubIFDDirectory.TAG_EXPOSURE_TIME));
		assertEquals(new Rational(-1, 3), sub.getRational(ExifSubIFDDirectory.TAG_EXPOSURE_BIAS));
		assertEquals("2.32", sub.getDescription(ExifSubIFDDirectory.TAG_EXIF_VERSION));
		assertEquals("Hello", sub.getDescription(ExifSubIFDDirectory.TAG_USER_COMMENT));
		assertEquals("2.300", gps.getDescription(GpsDirectory.TAG_VERSION_ID));
		assertEquals(52.503472, gps.getGeoLocation().getLatitude(), 1e-6);
		assertEquals(13.41, gps.getGeoLocation().getLongitude(), 1e-6);
		assertEquals(34.5, gps.getRational(GpsDirectory.TAG_ALTITUDE).doubleValue(), 1e-9);
		assertEquals("GPS", gps.getDescription(GpsDirectory.TAG_PROCESSING_METHOD));
		assertEquals(List.of(), MetadataExtractorOracle.errors(me));
	}

	@Test
	@DisplayName("Non-ASCII UserComment is stored as UNICODE in the EXIF byte order")
	void userCommentUnicode() throws Exception {
		JpegExif exif = new JpegExif();
		exif.addField(ExifTag.USER_COMMENT, "Grüße");
		TiffField<?> field = exif.getField(ExifTag.USER_COMMENT);
		byte[] data = (byte[]) field.getData();
		assertEquals("UNICODE\0", new String(data, 0, 8, StandardCharsets.ISO_8859_1));
		assertEquals("Grüße", new String(data, 8, data.length - 8, StandardCharsets.UTF_16BE));

		byte[] out = transform(read("images/1.jpg"), (is, os) -> Metadata.insertExif(is, os, exif, true));
		byte[] stored = MetadataExtractorOracle.read(out).getFirstDirectoryOfType(ExifSubIFDDirectory.class)
				.getByteArray(ExifSubIFDDirectory.TAG_USER_COMMENT);
		assertArrayEquals(data, stored);
	}

	@Test
	@DisplayName("addField picks the IFD from the tag class; getField and removeField follow")
	void addGetRemove() {
		JpegExif exif = new JpegExif();
		exif.addField(TiffTag.ARTIST, "a");
		exif.addField(ExifTag.FNUMBER, 4);
		exif.addField(GPSTag.GPS_STATUS, "A");
		exif.addField(InteropTag.INTEROPERABILITY_INDEX, "R98");
		assertNotNull(exif.getImageIFD().getField(TiffTag.ARTIST));
		assertNotNull(exif.getExifIFD().getField(ExifTag.FNUMBER));
		assertNotNull(exif.getGPSIFD().getField(GPSTag.GPS_STATUS));
		assertNotNull(exif.getInteropIFD().getField(InteropTag.INTEROPERABILITY_INDEX));
		assertEquals("a", ascii(exif.getField(TiffTag.ARTIST)));
		assertNotNull(exif.removeField(ExifTag.FNUMBER));
		assertNull(exif.getField(ExifTag.FNUMBER));
		assertNull(exif.removeField(ExifTag.FNUMBER));
		// Replacing keeps one field per tag
		exif.addField(TiffTag.ARTIST, "b");
		assertEquals("b", ascii(exif.getField(TiffTag.ARTIST)));
		assertEquals(1, exif.getImageIFD().getFields().size());
	}

	/** ASCII fields carry the TIFF NUL terminator in their data; strip it for comparison. */
	private static String ascii(TiffField<?> field) {
		return ((String) field.getData()).replace("\0", "");
	}

	@Test
	@DisplayName("Explicit-type setters coerce too, and reject unknown types and bad values")
	void explicitTypeSetters() {
		JpegExif exif = new JpegExif();
		exif.addExifField(ExifTag.ISO_SPEED_RATINGS, FieldType.SHORT, 400); // used to require short[]
		assertArrayEquals(new short[] {400}, (short[]) exif.getField(ExifTag.ISO_SPEED_RATINGS).getData());
		exif.addExifField(ExifTag.ISO_SPEED_RATINGS, FieldType.SHORT, new short[] {800}); // the old way still works
		assertArrayEquals(new short[] {800}, (short[]) exif.getField(ExifTag.ISO_SPEED_RATINGS).getData());
		assertThrows(IllegalArgumentException.class, () -> exif.addExifField(ExifTag.FNUMBER, FieldType.UNKNOWN, 2.8));
		assertThrows(IllegalArgumentException.class, () -> exif.addExifField(ExifTag.FNUMBER, FieldType.RATIONAL, "x"));
		assertThrows(IllegalArgumentException.class, () -> exif.addField(ExifTag.EXPAND_LENS, "x"));
		assertThrows(IllegalArgumentException.class, () -> exif.addField(ExifTag.FNUMBER, null));
		Tag foreign = new Tag() {
			public String getFieldAsString(Object value) { return ""; }
			public FieldType getFieldType() { return FieldType.ASCII; }
			public String getName() { return "foreign"; }
			public short getValue() { return 1; }
			public boolean isCritical() { return false; }
		};
		assertThrows(IllegalArgumentException.class, () -> exif.addField(foreign, "x"));
	}

	@Test
	@DisplayName("Windows XP tags take a string and store UTF-16LE")
	void windowsXp() throws Exception {
		JpegExif exif = new JpegExif();
		exif.addField(TiffTag.WINDOWS_XP_TITLE, "Titel ä");
		byte[] data = (byte[]) exif.getField(TiffTag.WINDOWS_XP_TITLE).getData();
		assertEquals("Titel ä\0", new String(data, StandardCharsets.UTF_16LE));
		byte[] out = transform(read("images/1.jpg"), (is, os) -> Metadata.insertExif(is, os, exif, true));
		ExifIFD0Directory ifd0 = MetadataExtractorOracle.read(out).getFirstDirectoryOfType(ExifIFD0Directory.class);
		assertEquals("Titel ä", ifd0.getDescription(ExifIFD0Directory.TAG_WIN_TITLE));
		assertTrue(Arrays.equals(data, ifd0.getByteArray(ExifIFD0Directory.TAG_WIN_TITLE)));
	}
}
