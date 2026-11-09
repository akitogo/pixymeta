/**
 * Copyright (C) 2026 akitogo GmbH.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Change History - most recent changes go on top of previous changes
 *
 * TagNames.java
 *
 * Who   Date       Description
 * ====  =========  =====================================================
 * AK    09Nov2026  Initial creation
 */

package pixy.meta.edit;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import pixy.image.tiff.Tag;
import pixy.image.tiff.TiffTag;
import pixy.meta.exif.ExifTag;
import pixy.meta.exif.GPSTag;
import pixy.meta.exif.InteropTag;
import pixy.meta.iptc.IPTCApplicationTag;

/**
 * Resolves EXIF and IPTC tag names as users and other libraries write them to PIXYMETA's tag
 * enums. Matching ignores case and every character that is not a letter or digit, so the EXIF
 * specification name ({@code DateTimeOriginal}), the display name metadata-extractor and exiftool
 * use ({@code Date/Time Original}, {@code By-line}), the PIXYMETA enum constant
 * ({@code DATE_TIME_ORIGINAL}) and a handful of common aliases ({@code ISO}, {@code Caption},
 * {@code Copyright} for IPTC) all resolve to the same tag.
 * <p>
 * EXIF names may carry a prefix to pick the directory when a name is ambiguous or numeric:
 * {@code exif:}, {@code gps:}, {@code interop:} or {@code ifd0:} (also {@code tiff:} /
 * {@code image:}). Numeric ids are accepted as decimal or {@code 0x} hexadecimal and are looked up
 * in the EXIF, then TIFF, then GPS enums. IPTC names may carry {@code iptc:} and may be given as
 * the data set number ({@code 25}) or {@code record:dataset} ({@code 2:25}).
 */
public final class TagNames {
	private static final Map<String, Tag> EXIF = new HashMap<String, Tag>();
	private static final Map<String, IPTCApplicationTag> IPTC = new HashMap<String, IPTCApplicationTag>();

	static {
		// Later puts do not override earlier ones: ExifTag wins over TiffTag for the few names both
		// define (DateTimeOriginal, WindowsXP*) because the EXIF SubIFD is where they belong.
		for(ExifTag tag : ExifTag.values()) registerExif(tag, tag.name());
		for(TiffTag tag : TiffTag.values()) registerExif(tag, tag.name());
		for(GPSTag tag : GPSTag.values()) registerExif(tag, tag.name());
		for(InteropTag tag : InteropTag.values()) registerExif(tag, tag.name());
		// metadata-extractor display names and exiftool names that differ from the enum names
		alias(TiffTag.IMAGE_LENGTH, "ImageHeight");
		alias(TiffTag.MIN_SAMPLE_VALUE, "MinimumSampleValue");
		alias(TiffTag.MAX_SAMPLE_VALUE, "MaximumSampleValue");
		alias(TiffTag.SUB_IFDS, "SubIFDPointer(s)", "SubIFDPointers");
		alias(TiffTag.JPEG_TABLES, "JPEGTables");
		alias(TiffTag.XMP, "ApplicationNotes");
		alias(TiffTag.IPTC, "IPTC/NAA", "IPTC");
		alias(TiffTag.ICC_PROFILE, "InterColorProfile");
		alias(TiffTag.DATETIME, "ModifyDate");
		alias(TiffTag.PHOTOSHOP, "PhotoshopSettings");
		alias(ExifTag.OECF, "Opto-electricConversionFunction(OECF)", "OptoElectricConversionFunction");
		alias(ExifTag.OFFSET_TIME, "TimeZone");
		alias(ExifTag.OFFSET_TIME_ORIGINAL, "TimeZoneOriginal");
		alias(ExifTag.OFFSET_TIME_DIGITIZED, "TimeZoneDigitized");
		alias(ExifTag.COMPONENT_CONFIGURATION, "ComponentsConfiguration");
		alias(ExifTag.BRIGHTNESS_VALUE, "BrightnessValue");
		alias(ExifTag.WHITE_BALENCE, "WhiteBalance", "WhiteBalanceMode");
		alias(ExifTag.FOCAL_PLANE_X_RESOLUTION, "FocalPlaneXResolution");
		alias(ExifTag.FOCAL_PLANE_Y_RESOLUTION, "FocalPlaneYResolution");
		alias(ExifTag.FOCAL_PLANE_RESOLUTION_UNIT, "FocalPlaneResolutionUnit");
		alias(ExifTag.FOCAL_LENGTH_IN_35MM_FORMAT, "FocalLength35", "FocalLengthIn35mmFilm");
		alias(ExifTag.IMAGE_UNIQUE_ID, "UniqueImageID");
		alias(ExifTag.OWNER_NAME, "CameraOwnerName");
		alias(ExifTag.ISO_SPEED_RATINGS, "ISO", "PhotographicSensitivity", "ISOSpeedRating");
		alias(ExifTag.EXIF_IMAGE_WIDTH, "PixelXDimension");
		alias(ExifTag.EXIF_IMAGE_HEIGHT, "PixelYDimension");
		alias(ExifTag.DATE_TIME_DIGITIZED, "CreateDate");
		alias(ExifTag.SENSITIVITY_TYPE, "SensitivityType");
		alias(ExifTag.STANDARD_OUTPUT_SENSITIVITY, "StandardOutputSensitivity");
		alias(ExifTag.RECOMMENDED_EXPOSURE_INDEX, "RecommendedExposureIndex");
		alias(GPSTag.GPS_DOP, "GPSDOP");
		alias(GPSTag.GPS_HPOSITIONING_ERROR, "GPSHorizontalPositioningError");
		alias(InteropTag.RELATED_IMAGE_LENGTH, "RelatedImageHeight");

		for(IPTCApplicationTag tag : IPTCApplicationTag.values()) {
			if(tag == IPTCApplicationTag.UNKNOWN) continue;
			IPTC.putIfAbsent(normalize(tag.getName()), tag);
			IPTC.putIfAbsent(normalize(tag.name()), tag);
		}
		alias(IPTCApplicationTag.OBJECT_TYPE_REF, "ObjectTypeReference");
		alias(IPTCApplicationTag.OBJECT_ATTR_REF, "ObjectAttributeReference");
		alias(IPTCApplicationTag.OBJECT_NAME, "Title");
		alias(IPTCApplicationTag.SUPP_CATEGORY, "SupplementalCategory(s)", "SupplementalCategory");
		alias(IPTCApplicationTag.FIXTURE_ID, "FixtureIdentifier");
		alias(IPTCApplicationTag.KEY_WORDS, "Keyword");
		alias(IPTCApplicationTag.SPECIAL_INSTRUCTIONS, "Instructions");
		alias(IPTCApplicationTag.DIGITAL_CREATION_DATE, "DigitalDateCreated");
		alias(IPTCApplicationTag.DIGITAL_CREATION_TIME, "DigitalTimeCreated");
		alias(IPTCApplicationTag.BY_LINE, "Author", "Creator");
		alias(IPTCApplicationTag.BY_LINE_TITLE, "AuthorsPosition");
		alias(IPTCApplicationTag.PROVINCE_STATE, "State");
		alias(IPTCApplicationTag.COUNTRY_CODE, "Country/PrimaryLocationCode");
		alias(IPTCApplicationTag.COUNTRY_NAME, "Country/PrimaryLocationName", "Country");
		alias(IPTCApplicationTag.ORIGINAL_TRANSMISSION_REF, "OriginalTransmissionReference", "TransmissionReference");
		alias(IPTCApplicationTag.COPYRIGHT_NOTICE, "Copyright");
		alias(IPTCApplicationTag.CAPTION_ABSTRACT, "Caption", "Description");
		alias(IPTCApplicationTag.WRITER_EDITOR, "CaptionWriter/Editor", "CaptionWriter");
		alias(IPTCApplicationTag.LANGUAGE_ID, "LanguageIdentifier");
		alias(IPTCApplicationTag.JOB_ID, "JobIdentifier");
		alias(IPTCApplicationTag.MASTER_DOCUMENT_ID, "MasterDocumentIdentifier");
		alias(IPTCApplicationTag.SHORT_DOCUMENT_ID, "ShortDocumentIdentifier");
		alias(IPTCApplicationTag.UNIQUE_DOCUMENT_ID, "UniqueDocumentIdentifier");
		alias(IPTCApplicationTag.OWNER_ID, "OwnerIdentifier");
	}

	private TagNames() {}

	private static void registerExif(Tag tag, String constantName) {
		if("UNKNOWN".equals(constantName)) return;
		EXIF.putIfAbsent(normalize(tag.getName()), tag);
		EXIF.putIfAbsent(normalize(constantName), tag);
	}

	private static void alias(Tag tag, String... names) {
		for(String name : names) EXIF.put(normalize(name), tag);
	}

	private static void alias(IPTCApplicationTag tag, String... names) {
		for(String name : names) IPTC.put(normalize(name), tag);
	}

	/** Lower-cases the name and drops every character that is not a letter or a digit. */
	public static String normalize(String name) {
		StringBuilder sb = new StringBuilder(name.length());
		for(int i = 0; i < name.length(); i++) {
			char c = name.charAt(i);
			if(Character.isLetterOrDigit(c)) sb.append(Character.toLowerCase(c));
		}
		return sb.toString();
	}

	/**
	 * Resolves an EXIF, TIFF, GPS or Interoperability tag name.
	 *
	 * @throws IllegalArgumentException if the name is unknown
	 */
	public static Tag exif(String name) {
		Tag tag = exifOrNull(name);
		if(tag == null) throw new IllegalArgumentException("Unknown EXIF tag '" + name + "'");
		return tag;
	}

	/** Like {@link #exif(String)} but returns null for an unknown name. */
	public static Tag exifOrNull(String name) {
		if(name == null) return null;
		String s = name.trim();
		String prefix = null;
		int colon = s.indexOf(':');
		if(colon > 0) {
			String p = s.substring(0, colon).trim().toLowerCase(Locale.ROOT);
			if(p.equals("exif") || p.equals("gps") || p.equals("interop") || p.equals("interoperability")
					|| p.equals("ifd0") || p.equals("tiff") || p.equals("image")) {
				prefix = p;
				s = s.substring(colon + 1).trim();
			}
		}
		Integer number = parseNumber(s);
		if(number != null) {
			short id = (short)number.intValue();
			if(number < 0 || number > 0xffff) return null;
			if(prefix == null || prefix.equals("exif")) {
				Tag t = ExifTag.fromShort(id);
				if(t != TiffTag.UNKNOWN) return t;
				if(prefix != null) return null;
			}
			if(prefix == null || prefix.equals("ifd0") || prefix.equals("tiff") || prefix.equals("image")) {
				Tag t = TiffTag.fromShort(id);
				if(t != TiffTag.UNKNOWN) return t;
				if(prefix != null) return null;
			}
			if(prefix == null || prefix.equals("gps")) {
				Tag t = GPSTag.fromShort(id);
				if(t != TiffTag.UNKNOWN) return t;
				if(prefix != null) return null;
			}
			if(prefix != null) {
				Tag t = InteropTag.fromShort(id);
				return t == TiffTag.UNKNOWN ? null : t;
			}
			return null;
		}
		Tag tag = EXIF.get(normalize(s));
		if(prefix == null) return tag;
		// With a prefix, the tag must belong to that directory; fall back to that enum's own names
		Tag[] values;
		Class<? extends Tag> wanted;
		if(prefix.equals("exif")) { wanted = ExifTag.class; values = ExifTag.values(); }
		else if(prefix.equals("gps")) { wanted = GPSTag.class; values = GPSTag.values(); }
		else if(prefix.equals("interop") || prefix.equals("interoperability")) { wanted = InteropTag.class; values = InteropTag.values(); }
		else { wanted = TiffTag.class; values = TiffTag.values(); }
		if(tag != null && wanted.isInstance(tag)) return tag;
		return lookupInEnumByName(values, s);
	}
	
	private static Tag lookupInEnumByName(Tag[] values, String name) {
		String n = normalize(name);
		for(Tag t : values)
			if(!t.getName().equals("Unknown") && (normalize(t.getName()).equals(n) || normalize(((Enum<?>)t).name()).equals(n)))
				return t;
		return null;
	}

	/**
	 * Resolves an IPTC application record (record 2) data set name.
	 *
	 * @throws IllegalArgumentException if the name is unknown
	 */
	public static IPTCApplicationTag iptc(String name) {
		IPTCApplicationTag tag = iptcOrNull(name);
		if(tag == null) throw new IllegalArgumentException("Unknown IPTC tag '" + name + "'");
		return tag;
	}

	/** Like {@link #iptc(String)} but returns null for an unknown name. */
	public static IPTCApplicationTag iptcOrNull(String name) {
		if(name == null) return null;
		String s = name.trim();
		if(s.regionMatches(true, 0, "iptc:", 0, 5)) s = s.substring(5).trim();
		int colon = s.indexOf(':');
		if(colon > 0 && parseNumber(s.substring(0, colon).trim()) != null) {
			Integer record = parseNumber(s.substring(0, colon).trim());
			if(record == null || record != 2) return null;
			s = s.substring(colon + 1).trim();
		}
		Integer number = parseNumber(s);
		if(number != null) {
			IPTCApplicationTag tag = IPTCApplicationTag.fromTag(number);
			return tag == IPTCApplicationTag.UNKNOWN ? null : tag;
		}
		return IPTC.get(normalize(s));
	}

	private static Integer parseNumber(String s) {
		if(s.isEmpty()) return null;
		try {
			if(s.length() > 2 && (s.startsWith("0x") || s.startsWith("0X")))
				return Integer.parseInt(s.substring(2), 16);
			for(int i = 0; i < s.length(); i++)
				if(!Character.isDigit(s.charAt(i))) return null;
			return Integer.parseInt(s);
		} catch (NumberFormatException e) {
			return null;
		}
	}
}
