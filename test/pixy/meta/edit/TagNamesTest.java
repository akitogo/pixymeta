package pixy.meta.edit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.drew.metadata.Directory;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.drew.metadata.exif.ExifInteropDirectory;
import com.drew.metadata.exif.GpsDirectory;
import com.drew.metadata.iptc.IptcDirectory;

import pixy.image.tiff.Tag;
import pixy.image.tiff.TiffTag;
import pixy.meta.exif.ExifTag;
import pixy.meta.exif.GPSTag;
import pixy.meta.exif.InteropTag;
import pixy.meta.iptc.IPTCApplicationTag;

/**
 * Tag names in every common spelling resolve to the right PIXYMETA tag. metadata-extractor's
 * display names (the keys bx-image exposes) are checked exhaustively against its own tag tables.
 */
public class TagNamesTest {

	/** metadata-extractor names that deliberately resolve to the specification's tag of that name, not to its own id. */
	private static final Set<String> KNOWN_DIFFERENCES = Set.of(
			"White Balance",     // metadata-extractor: 0x9208 LightSource; the spec's WhiteBalance is 0xa403
			"Subject Location"); // metadata-extractor: 0x9214 SubjectArea; the spec's SubjectLocation is 0xa214

	@Test
	@DisplayName("Every metadata-extractor EXIF, GPS and Interop tag name resolves to the tag with the same id")
	void metadataExtractorExifNames() {
		List<String> problems = new ArrayList<>();
		check(new ExifIFD0Directory(), problems);
		check(new GpsDirectory(), problems);
		check(new ExifInteropDirectory(), problems);
		assertEquals(List.of(), problems);
	}

	private static void check(Directory dir, List<String> problems) {
		for (int id = 0; id <= 0xFFFF; id++) {
			if (!dir.hasTagName(id)) continue;
			boolean known = known(dir, id);
			if (!known) continue; // a tag PIXYMETA has no enum constant for
			String name = dir.getTagName(id);
			if (KNOWN_DIFFERENCES.contains(name)) continue;
			Tag tag = dir instanceof GpsDirectory ? TagNames.exifOrNull("gps:" + name)
					: dir instanceof ExifInteropDirectory ? TagNames.exifOrNull("interop:" + name)
					: TagNames.exifOrNull(name);
			if (tag == null) problems.add(dir.getName() + " 0x" + Integer.toHexString(id) + " '" + name + "' does not resolve");
			else if ((tag.getValue() & 0xffff) != id)
				problems.add(dir.getName() + " 0x" + Integer.toHexString(id) + " '" + name + "' resolves to " + tag.getName() + " 0x" + Integer.toHexString(tag.getValue() & 0xffff));
		}
	}

	private static boolean known(Directory dir, int id) {
		short s = (short) id;
		if (dir instanceof GpsDirectory) return GPSTag.fromShort(s) != TiffTag.UNKNOWN;
		if (dir instanceof ExifInteropDirectory) return InteropTag.fromShort(s) != TiffTag.UNKNOWN;
		return ExifTag.fromShort(s) != TiffTag.UNKNOWN || TiffTag.fromShort(s) != TiffTag.UNKNOWN;
	}

	@Test
	@DisplayName("Every metadata-extractor IPTC application record name resolves to the same data set")
	void metadataExtractorIptcNames() {
		IptcDirectory dir = new IptcDirectory();
		List<String> problems = new ArrayList<>();
		for (int id = 0x0200; id <= 0x02FF; id++) {
			if (!dir.hasTagName(id)) continue;
			int dataSet = id & 0xff;
			if (IPTCApplicationTag.fromTag(dataSet) == IPTCApplicationTag.UNKNOWN) continue;
			String name = dir.getTagName(id);
			IPTCApplicationTag tag = TagNames.iptcOrNull(name);
			if (tag == null) problems.add("2:" + dataSet + " '" + name + "' does not resolve");
			else if (tag.getTag() != dataSet) problems.add("2:" + dataSet + " '" + name + "' resolves to " + tag.getName());
		}
		assertEquals(List.of(), problems);
	}

	@Test
	@DisplayName("Specification names, enum constants, aliases, prefixes and numbers")
	void spellings() {
		assertSame(ExifTag.DATE_TIME_ORIGINAL, TagNames.exif("DateTimeOriginal"));
		assertSame(ExifTag.DATE_TIME_ORIGINAL, TagNames.exif("Date/Time Original"));
		assertSame(ExifTag.DATE_TIME_ORIGINAL, TagNames.exif("date_time_original"));
		assertSame(ExifTag.DATE_TIME_ORIGINAL, TagNames.exif("0x9003"));
		assertSame(ExifTag.DATE_TIME_ORIGINAL, TagNames.exif("36867"));
		assertSame(ExifTag.DATE_TIME_ORIGINAL, TagNames.exif("exif:DateTimeOriginal"));
		assertSame(TiffTag.DATE_TIME_ORIGINAL, TagNames.exif("ifd0:DateTimeOriginal"));
		assertSame(ExifTag.FNUMBER, TagNames.exif("F-Number"));
		assertSame(ExifTag.ISO_SPEED_RATINGS, TagNames.exif("ISO"));
		assertSame(ExifTag.ISO_SPEED_RATINGS, TagNames.exif("PhotographicSensitivity"));
		assertSame(ExifTag.WHITE_BALENCE, TagNames.exif("WhiteBalance"));
		assertSame(ExifTag.FOCAL_LENGTH_IN_35MM_FORMAT, TagNames.exif("FocalLengthIn35mmFilm"));
		assertSame(TiffTag.ARTIST, TagNames.exif("Artist"));
		assertSame(TiffTag.COPYRIGHT, TagNames.exif(" copyright "));
		assertSame(TiffTag.IMAGE_LENGTH, TagNames.exif("ImageHeight"));
		assertSame(TiffTag.DATETIME, TagNames.exif("ModifyDate"));
		assertSame(GPSTag.GPS_LATITUDE, TagNames.exif("GPS Latitude"));
		assertSame(GPSTag.GPS_LATITUDE, TagNames.exif("gps:2"));
		assertSame(InteropTag.INTEROPERABILITY_INDEX, TagNames.exif("interop:1"));
		assertSame(TiffTag.ORIENTATION, TagNames.exif("tiff:0x0112"));
		assertNull(TagNames.exifOrNull("gps:Artist"));
		assertNull(TagNames.exifOrNull("NoSuchTag"));
		assertNull(TagNames.exifOrNull(""));
		assertNull(TagNames.exifOrNull(null));
		assertThrows(IllegalArgumentException.class, () -> TagNames.exif("NoSuchTag"));

		assertSame(IPTCApplicationTag.KEY_WORDS, TagNames.iptc("Keywords"));
		assertSame(IPTCApplicationTag.KEY_WORDS, TagNames.iptc("keyword"));
		assertSame(IPTCApplicationTag.KEY_WORDS, TagNames.iptc("iptc:Keywords"));
		assertSame(IPTCApplicationTag.KEY_WORDS, TagNames.iptc("25"));
		assertSame(IPTCApplicationTag.KEY_WORDS, TagNames.iptc("2:25"));
		assertSame(IPTCApplicationTag.BY_LINE, TagNames.iptc("By-line"));
		assertSame(IPTCApplicationTag.BY_LINE, TagNames.iptc("Creator"));
		assertSame(IPTCApplicationTag.CAPTION_ABSTRACT, TagNames.iptc("Caption/Abstract"));
		assertSame(IPTCApplicationTag.CAPTION_ABSTRACT, TagNames.iptc("Description"));
		assertSame(IPTCApplicationTag.COPYRIGHT_NOTICE, TagNames.iptc("Copyright"));
		assertSame(IPTCApplicationTag.COUNTRY_NAME, TagNames.iptc("Country/Primary Location Name"));
		assertSame(IPTCApplicationTag.SUPP_CATEGORY, TagNames.iptc("Supplemental Category(s)"));
		assertSame(IPTCApplicationTag.JOB_ID, TagNames.iptc("Job Identifier"));
		assertNull(TagNames.iptcOrNull("1:90"));
		assertNull(TagNames.iptcOrNull("NoSuchTag"));
		assertThrows(IllegalArgumentException.class, () -> TagNames.iptc("NoSuchTag"));
	}

	@Test
	@DisplayName("Normalisation drops case and punctuation")
	void normalize() {
		assertEquals("datetimeoriginal", TagNames.normalize("Date/Time Original"));
		assertEquals("byline", TagNames.normalize("By-line"));
		assertEquals("isospeedratings", TagNames.normalize("ISO_SPEED_RATINGS"));
		assertNotNull(TagNames.normalize(""));
	}
}
