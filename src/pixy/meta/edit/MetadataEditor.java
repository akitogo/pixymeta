/**
 * Copyright (C) 2026 akitogo GmbH.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Change History - most recent changes go on top of previous changes
 *
 * MetadataEditor.java
 *
 * Who   Date       Description
 * ====  =========  =====================================================
 * AK    09Nov2026  Initial creation
 */

package pixy.meta.edit;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Array;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import pixy.image.ImageType;
import pixy.image.tiff.FieldType;
import pixy.image.tiff.IFD;
import pixy.image.tiff.Tag;
import pixy.image.tiff.TiffField;
import pixy.image.tiff.TiffTag;
import pixy.io.PeekHeadInputStream;
import pixy.meta.Metadata;
import pixy.meta.MetadataType;
import pixy.meta.MetadataUpdate;
import pixy.meta.exif.Exif;
import pixy.meta.exif.ExifTag;
import pixy.meta.exif.ExifThumbnail;
import pixy.meta.exif.GPSTag;
import pixy.meta.exif.InteropTag;
import pixy.meta.image.Comments;
import pixy.meta.iptc.IPTC;
import pixy.meta.iptc.IPTCApplicationTag;
import pixy.meta.iptc.IPTCDataSet;
import pixy.meta.iptc.IPTCRecord;
import pixy.meta.jpeg.JpegExif;
import pixy.meta.xmp.XMP;
import pixy.string.XMLUtils;
import pixy.util.MetadataUtils;

/**
 * Edits the metadata of an image by name and value, without decoding or re-encoding the image.
 * <p>
 * The editor is created from the image ({@link #read}) or empty ({@link #create()}), holds a
 * working copy of the EXIF, IPTC, XMP, ICC profile and comments, takes changes through setters
 * that accept plain values and tag names in any common spelling (see {@link TagNames}), and
 * finally {@link #apply applies} the result to an image. Applying writes the whole working copy,
 * so a tag removed from the editor disappears from the file and metadata the editor was not told
 * about stays as it was. Image data is copied byte for byte.
 * <pre>
 *   MetadataEditor editor = MetadataEditor.read(Paths.get("photo.jpg"));
 *   editor.setExifTag("Copyright", "(c) 2026 akitogo")
 *         .setExifTag("Date/Time Original", LocalDateTime.now())
 *         .setExifTag("GPSLatitude", 52.503472)        // decimal degrees set the Ref too
 *         .setIptcTag("Keywords", List.of("garden", "spring"))
 *         .setXmp(xmp)
 *         .apply(Paths.get("photo.jpg"));              // in place, via a temporary file
 * </pre>
 * Which metadata a format can carry is checked when a setter is called (if the source format is
 * known) and again when the result is applied, and an unsupported combination such as EXIF in a
 * PNG fails with {@link UnsupportedOperationException} instead of silently doing nothing.
 * Removing a type the format cannot hold is a no-op.
 * <p>
 * Instances are not thread-safe.
 */
public final class MetadataEditor {

	private static final Map<ImageType, Set<MetadataType>> WRITABLE = new EnumMap<ImageType, Set<MetadataType>>(ImageType.class);
	private static final Map<ImageType, Set<MetadataType>> REMOVABLE = new EnumMap<ImageType, Set<MetadataType>>(ImageType.class);

	static {
		WRITABLE.put(ImageType.JPG, EnumSet.of(MetadataType.EXIF, MetadataType.IPTC, MetadataType.XMP, MetadataType.ICC_PROFILE, MetadataType.COMMENT));
		WRITABLE.put(ImageType.TIFF, EnumSet.of(MetadataType.EXIF, MetadataType.IPTC, MetadataType.XMP, MetadataType.ICC_PROFILE, MetadataType.COMMENT));
		WRITABLE.put(ImageType.PNG, EnumSet.of(MetadataType.XMP, MetadataType.ICC_PROFILE, MetadataType.COMMENT));
		WRITABLE.put(ImageType.GIF, EnumSet.of(MetadataType.XMP, MetadataType.COMMENT));
		WRITABLE.put(ImageType.WEBP, EnumSet.of(MetadataType.EXIF, MetadataType.XMP, MetadataType.ICC_PROFILE));
		REMOVABLE.put(ImageType.JPG, EnumSet.of(MetadataType.EXIF, MetadataType.IPTC, MetadataType.XMP, MetadataType.ICC_PROFILE, MetadataType.COMMENT,
				MetadataType.PHOTOSHOP_IRB, MetadataType.JPG_JFIF, MetadataType.JPG_DUCKY, MetadataType.JPG_ADOBE));
		REMOVABLE.put(ImageType.TIFF, EnumSet.of(MetadataType.EXIF, MetadataType.IPTC, MetadataType.XMP, MetadataType.ICC_PROFILE, MetadataType.COMMENT,
				MetadataType.PHOTOSHOP_IRB));
		REMOVABLE.put(ImageType.PNG, EnumSet.of(MetadataType.EXIF, MetadataType.IPTC, MetadataType.XMP, MetadataType.ICC_PROFILE, MetadataType.COMMENT,
				MetadataType.PHOTOSHOP_IRB, MetadataType.PNG_TEXTUAL, MetadataType.PNG_TIME));
		REMOVABLE.put(ImageType.GIF, EnumSet.of(MetadataType.XMP, MetadataType.ICC_PROFILE, MetadataType.COMMENT));
		REMOVABLE.put(ImageType.WEBP, EnumSet.of(MetadataType.EXIF, MetadataType.XMP, MetadataType.ICC_PROFILE));
	}

	/** Metadata types that can be written into images of the given format. */
	public static Set<MetadataType> writableTypes(ImageType type) {
		Set<MetadataType> set = WRITABLE.get(type);
		return set == null ? Collections.<MetadataType>emptySet() : Collections.unmodifiableSet(set);
	}

	/** Metadata types that can be removed from images of the given format. */
	public static Set<MetadataType> removableTypes(ImageType type) {
		Set<MetadataType> set = REMOVABLE.get(type);
		return set == null ? Collections.<MetadataType>emptySet() : Collections.unmodifiableSet(set);
	}

	/** True if {@link Metadata#readMetadata} and {@link #apply} handle the format at all. */
	public static boolean isSupported(ImageType type) {
		return WRITABLE.containsKey(type);
	}

	// ---------------------------------------------------------------- state

	private final ImageType sourceType; // null for an editor created without an image
	private final Set<MetadataType> sourceTypes;

	private JpegExif exif;          // working copy, null = none
	private boolean exifDirty;
	private List<IPTCDataSet> iptc; // working copy, null = none
	private boolean iptcDirty;
	private String xmp;
	private boolean xmpDirty;
	private byte[] icc;
	private boolean iccDirty;
	private List<String> comments;
	private boolean commentsDirty;
	private final Set<MetadataType> removals = EnumSet.noneOf(MetadataType.class);

	private MetadataEditor(ImageType sourceType, Set<MetadataType> sourceTypes) {
		this.sourceType = sourceType;
		this.sourceTypes = sourceTypes;
	}

	// ---------------------------------------------------------------- factories

	/** An editor without an image: everything set on it is written when it is applied. */
	public static MetadataEditor create() {
		return new MetadataEditor(null, EnumSet.noneOf(MetadataType.class));
	}

	/**
	 * Reads the metadata of an image. The stream is read up to the image data (JPEG) or to the end
	 * (other formats) and is not closed.
	 *
	 * @throws IOException if the stream cannot be read or is not a supported image format
	 */
	public static MetadataEditor read(InputStream is) throws IOException {
		PeekHeadInputStream peek = new PeekHeadInputStream(is, Metadata.IMAGE_MAGIC_NUMBER_LEN, MetadataUtils.IMAGE_HEADER_PEEK_LEN);
		ImageType type = MetadataUtils.guessImageType(peek);
		if(!isSupported(type))
			throw new IOException("Unsupported image format: " + type);
		Map<MetadataType, Metadata> read = Metadata.readMetadata(peek);
		peek.shallowClose();
		MetadataEditor editor = new MetadataEditor(type, EnumSet.noneOf(MetadataType.class));
		editor.load(read);
		return editor;
	}

	public static MetadataEditor read(byte[] image) throws IOException {
		return read(new ByteArrayInputStream(image));
	}

	public static MetadataEditor read(Path image) throws IOException {
		try (InputStream is = Files.newInputStream(image)) {
			return read(is);
		}
	}

	public static MetadataEditor read(File image) throws IOException {
		return read(image.toPath());
	}

	private void load(Map<MetadataType, Metadata> read) {
		for(Map.Entry<MetadataType, Metadata> e : read.entrySet()) {
			Metadata m = e.getValue();
			if(m == null || e.getKey() == MetadataType.IMAGE) continue; // IMAGE is derived information, not a metadata block
			switch(e.getKey()) {
				case EXIF:
					if(m instanceof Exif) exif = workingCopy((Exif)m, sourceType == ImageType.TIFF);
					break;
				case IPTC:
					if(m instanceof IPTC) {
						iptc = new ArrayList<IPTCDataSet>();
						for(List<IPTCDataSet> list : ((IPTC)m).getDataSets().values())
							iptc.addAll(list);
					}
					break;
				case XMP:
					if(m instanceof XMP) {
						byte[] data = m.getData();
						if(data != null && data.length > 0) xmp = new String(data, StandardCharsets.UTF_8);
					}
					break;
				case ICC_PROFILE:
					icc = m.getData();
					break;
				case COMMENT:
					if(m instanceof Comments) comments = new ArrayList<String>(((Comments)m).getComments());
					break;
				default:
			}
			sourceTypes.add(e.getKey());
		}
	}

	/**
	 * A mutable copy: the getters of a read Exif return unmodifiable IFD views, and a TIFF's IFD0
	 * contains the structural tags of the image, which must not travel with the metadata.
	 */
	private static JpegExif workingCopy(Exif src, boolean descriptiveOnly) {
		JpegExif out = new JpegExif();
		out.setPreferredEndian(src.getPreferredEndian());
		IFD image = copyIFD(src.getImageIFD(), descriptiveOnly, TiffTag.EXIF_SUB_IFD, TiffTag.GPS_SUB_IFD);
		if(image != null) out.setImageIFD(image);
		out.setExifIFD(copyIFD(src.getExifIFD(), false, ExifTag.EXIF_INTEROPERABILITY_OFFSET));
		out.setGPSIFD(copyIFD(src.getGPSIFD(), false));
		out.setInteropIFD(copyIFD(src.getInteropIFD(), false));
		ExifThumbnail thumbnail = src.getThumbnail();
		if(thumbnail != null && thumbnail.containsImage()) out.setThumbnail(thumbnail);
		return out;
	}

	private static IFD copyIFD(IFD src, boolean descriptiveOnly, Tag... skip) {
		if(src == null) return null;
		IFD out = new IFD();
		fields:
		for(TiffField<?> field : src.getFields()) {
			for(Tag s : skip)
				if(s.getValue() == field.getTag()) continue fields;
			if(descriptiveOnly) {
				Tag tag = TiffTag.fromShort(field.getTag());
				if(tag == TiffTag.UNKNOWN || tag.isCritical()) continue;
			}
			out.addField(field);
		}
		return out;
	}

	// ---------------------------------------------------------------- introspection

	/** The format of the image the editor was read from, or null for {@link #create()}. */
	public ImageType getSourceType() {
		return sourceType;
	}

	/** The metadata types the source image contained. */
	public Set<MetadataType> getSourceTypes() {
		return Collections.unmodifiableSet(sourceTypes);
	}

	/** The metadata types the editor currently holds (after edits). */
	public Set<MetadataType> getTypes() {
		Set<MetadataType> types = EnumSet.noneOf(MetadataType.class);
		if(exif != null && !isEmpty(exif)) types.add(MetadataType.EXIF);
		if(iptc != null && !iptc.isEmpty()) types.add(MetadataType.IPTC);
		if(xmp != null) types.add(MetadataType.XMP);
		if(icc != null) types.add(MetadataType.ICC_PROFILE);
		if(comments != null && !comments.isEmpty()) types.add(MetadataType.COMMENT);
		for(MetadataType t : sourceTypes)
			if(!removals.contains(t) && !isHandled(t)) types.add(t);
		return types;
	}

	private static boolean isHandled(MetadataType t) {
		return t == MetadataType.EXIF || t == MetadataType.IPTC || t == MetadataType.XMP
				|| t == MetadataType.ICC_PROFILE || t == MetadataType.COMMENT;
	}

	/** True once any setter or removal has been called. */
	public boolean hasChanges() {
		return exifDirty || iptcDirty || xmpDirty || iccDirty || commentsDirty || !removals.isEmpty();
	}

	private void checkWritable(MetadataType type) {
		if(sourceType != null && !writableTypes(sourceType).contains(type))
			throw new UnsupportedOperationException(type + " cannot be written to a " + sourceType + " image");
	}

	// ---------------------------------------------------------------- EXIF

	/** The working EXIF, or null. Changes made to the returned instance are written on apply. */
	public Exif getExif() {
		return exif;
	}

	/**
	 * Formatted value of an EXIF tag (see {@link TagNames#exif}), or null if the tag is absent.
	 * Enumerated tags are described (e.g. Orientation 6 as "RightTop"), rationals are reduced,
	 * everything else is the raw value as text.
	 */
	public String getExifTag(String name) {
		if(exif == null) return null;
		TiffField<?> field = exif.getField(TagNames.exif(name));
		return field == null ? null : format(TagNames.exif(name), field);
	}

	/** All EXIF tags as {PIXYMETA tag name: formatted value}, IFD0 first, then EXIF, GPS, Interop. */
	public Map<String, String> getExifValues() {
		Map<String, String> out = new LinkedHashMap<String, String>();
		if(exif == null) return out;
		addValues(out, exif.getImageIFD(), TiffTag.class);
		addValues(out, exif.getExifIFD(), ExifTag.class);
		addValues(out, exif.getGPSIFD(), GPSTag.class);
		addValues(out, exif.getInteropIFD(), InteropTag.class);
		return out;
	}

	private static void addValues(Map<String, String> out, IFD ifd, Class<? extends Tag> tagClass) {
		if(ifd == null) return;
		List<TiffField<?>> fields = new ArrayList<TiffField<?>>(ifd.getFields());
		Collections.sort(fields);
		for(TiffField<?> field : fields) {
			Tag tag = tagClass == TiffTag.class ? TiffTag.fromShort(field.getTag())
					: tagClass == ExifTag.class ? ExifTag.fromShort(field.getTag())
					: tagClass == GPSTag.class ? GPSTag.fromShort(field.getTag())
					: InteropTag.fromShort(field.getTag());
			if(tag == TiffTag.UNKNOWN || tag == TiffTag.EXIF_SUB_IFD || tag == TiffTag.GPS_SUB_IFD
					|| tag == ExifTag.EXIF_INTEROPERABILITY_OFFSET || tag == ExifTag.MAKER_NOTE) continue;
			out.put(tag.getName(), format(tag, field));
		}
	}

	private static String format(Tag tag, TiffField<?> field) {
		String s = null;
		try {
			FieldType type = field.getType();
			s = (type == FieldType.SHORT || type == FieldType.SSHORT) ? tag.getFieldAsString(field.getDataAsLong())
					: tag.getFieldAsString(field.getData());
		} catch (RuntimeException e) {
			s = null;
		}
		if(s == null || s.isEmpty()) s = field.getDataAsString();
		if(s == null) return null;
		s = s.replace("\0", "").trim();
		// Array fields print as "[a, b]"; a single value needs no brackets
		if(s.length() >= 2 && s.charAt(0) == '[' && s.charAt(s.length() - 1) == ']')
			s = s.substring(1, s.length() - 1).trim();
		return s;
	}

	/**
	 * Sets an EXIF tag. The name is resolved with {@link TagNames#exif}; the value is converted
	 * as {@link Exif#addField(Tag, Object)} describes (numbers, strings such as "1/250", dates,
	 * lists). A null value removes the tag. Two conveniences: GPSLatitude/GPSLongitude (and the
	 * Dest variants) given as one signed decimal number are stored as degrees/minutes/seconds and
	 * set the matching Ref tag (N/S, E/W); GPSAltitude given as a signed number sets GPSAltitudeRef.
	 *
	 * @throws IllegalArgumentException for an unknown tag, a structural TIFF tag, a pointer tag
	 *         or a value that cannot be converted
	 * @throws UnsupportedOperationException if the source image format cannot hold EXIF
	 */
	public MetadataEditor setExifTag(String name, Object value) {
		Tag tag = TagNames.exif(name);
		if(value == null) return removeExifTag(name);
		checkWritable(MetadataType.EXIF);
		checkSettable(tag);
		JpegExif target = exif != null ? exif : new JpegExif();
		if(!(tag instanceof GPSTag && setGpsConvenience(target, (GPSTag)tag, value)))
			target.addField(tag, value); // throws before anything is marked dirty
		exif = target;
		exifDirty = true;
		return this;
	}

	/** Sets several EXIF tags; see {@link #setExifTag}. */
	public MetadataEditor setExifTags(Map<String, ?> values) {
		for(Map.Entry<String, ?> e : values.entrySet()) setExifTag(e.getKey(), e.getValue());
		return this;
	}

	/** Removes one EXIF tag; a no-op if it is absent. */
	public MetadataEditor removeExifTag(String name) {
		Tag tag = TagNames.exif(name);
		if(exif != null && exif.getField(tag) != null) {
			exif.removeField(tag);
			exifDirty = true;
		}
		return this;
	}

	private static void checkSettable(Tag tag) {
		if(tag == TiffTag.EXIF_SUB_IFD || tag == TiffTag.GPS_SUB_IFD || tag == ExifTag.EXIF_INTEROPERABILITY_OFFSET
				|| tag == TiffTag.IPTC || tag == TiffTag.XMP || tag == TiffTag.ICC_PROFILE || tag == TiffTag.PHOTOSHOP
				|| tag == ExifTag.MAKER_NOTE || tag == TiffTag.JPEG_INTERCHANGE_FORMAT || tag == TiffTag.JPEG_INTERCHANGE_FORMAT_LENGTH)
			throw new IllegalArgumentException("Tag " + tag.getName() + " cannot be set directly; it is written by the library");
		if(tag instanceof TiffTag && tag.isCritical())
			throw new IllegalArgumentException("Tag " + tag.getName() + " describes the image structure and cannot be set as metadata");
	}

	private static boolean setGpsConvenience(JpegExif exif, GPSTag tag, Object value) {
		Double decimal = asSingleDecimal(value);
		if(decimal == null) return false;
		switch(tag) {
			case GPS_LATITUDE:
			case GPS_DEST_LATITUDE:
				exif.addField(tag, toDegreesMinutesSeconds(Math.abs(decimal)));
				exif.addField(tag == GPSTag.GPS_LATITUDE ? GPSTag.GPS_LATITUDE_REF : GPSTag.GPS_DEST_LATITUDE_REF, decimal < 0 ? "S" : "N");
				return true;
			case GPS_LONGITUDE:
			case GPS_DEST_LONGITUDE:
				exif.addField(tag, toDegreesMinutesSeconds(Math.abs(decimal)));
				exif.addField(tag == GPSTag.GPS_LONGITUDE ? GPSTag.GPS_LONGITUDE_REF : GPSTag.GPS_DEST_LONGITUDE_REF, decimal < 0 ? "W" : "E");
				return true;
			case GPS_ALTITUDE:
				exif.addField(tag, Math.abs(decimal));
				exif.addField(GPSTag.GPS_ALTITUDE_REF, decimal < 0 ? 1 : 0);
				return true;
			default:
				return false;
		}
	}

	private static Double asSingleDecimal(Object value) {
		if(value instanceof Number) return ((Number)value).doubleValue();
		if(value instanceof String) {
			String s = ((String)value).trim();
			if(s.indexOf(' ') >= 0 || s.indexOf(',') >= 0 || s.indexOf('/') >= 0 || s.isEmpty()) return null;
			try {
				return Double.parseDouble(s);
			} catch (NumberFormatException e) {
				return null;
			}
		}
		return null;
	}

	/** Decimal degrees to the three GPS rationals: whole degrees, whole minutes, seconds to 1/10000. */
	static int[] toDegreesMinutesSeconds(double decimal) {
		if(decimal < 0 || decimal > 180) throw new IllegalArgumentException("Coordinate out of range: " + decimal);
		int degrees = (int)Math.floor(decimal);
		double minutesDecimal = (decimal - degrees) * 60;
		int minutes = (int)Math.floor(minutesDecimal);
		long seconds = Math.round((minutesDecimal - minutes) * 60 * 10000);
		if(seconds >= 600000) { seconds -= 600000; minutes++; }
		if(minutes >= 60) { minutes -= 60; degrees++; }
		return new int[] {degrees, 1, minutes, 1, (int)seconds, 10000};
	}

	private static boolean isEmpty(Exif exif) {
		return count(exif.getImageIFD()) + count(exif.getExifIFD()) + count(exif.getGPSIFD()) + count(exif.getInteropIFD()) == 0
				&& (exif.getThumbnail() == null || !exif.getThumbnail().containsImage());
	}

	private static int count(IFD ifd) {
		return ifd == null ? 0 : ifd.getFields().size();
	}

	// ---------------------------------------------------------------- IPTC

	/** The working IPTC data sets (a copy), empty if there are none. */
	public List<IPTCDataSet> getIptc() {
		return iptc == null ? new ArrayList<IPTCDataSet>() : new ArrayList<IPTCDataSet>(iptc);
	}

	/** The values of an IPTC tag (see {@link TagNames#iptc}), empty if absent; repeatable tags may have several. */
	public List<String> getIptcTag(String name) {
		IPTCApplicationTag tag = TagNames.iptc(name);
		List<String> out = new ArrayList<String>();
		if(iptc == null) return out;
		for(IPTCDataSet d : iptc)
			if(isApplication(d) && d.getTag() == tag.getTag()) out.add(d.getDataAsString());
		return out;
	}

	/** All application record data sets as {PIXYMETA tag name: values}. */
	public Map<String, List<String>> getIptcValues() {
		Map<String, List<String>> out = new LinkedHashMap<String, List<String>>();
		if(iptc == null) return out;
		List<IPTCDataSet> sorted = new ArrayList<IPTCDataSet>(iptc);
		Collections.sort(sorted);
		for(IPTCDataSet d : sorted) {
			if(!isApplication(d) || d.getTag() == IPTCApplicationTag.RECORD_VERSION.getTag()) continue;
			List<String> values = out.get(d.getName());
			if(values == null) out.put(d.getName(), values = new ArrayList<String>());
			values.add(d.getDataAsString());
		}
		return out;
	}

	private static boolean isApplication(IPTCDataSet d) {
		return d.getRecordNumber() == IPTCRecord.APPLICATION.getRecordNumber();
	}

	private static final Set<IPTCApplicationTag> IPTC_DATE_TAGS = EnumSet.of(IPTCApplicationTag.DATE_CREATED,
			IPTCApplicationTag.RELEASE_DATE, IPTCApplicationTag.EXPIRATION_DATE, IPTCApplicationTag.DIGITAL_CREATION_DATE,
			IPTCApplicationTag.REFERENCE_DATE);
	private static final Set<IPTCApplicationTag> IPTC_TIME_TAGS = EnumSet.of(IPTCApplicationTag.TIME_CREATED,
			IPTCApplicationTag.RELEASE_TIME, IPTCApplicationTag.EXPIRATION_TIME, IPTCApplicationTag.DIGITAL_CREATION_TIME);

	/**
	 * Sets an IPTC application record tag, replacing all its existing data sets. The value is a
	 * string, a number, a date (formatted {@code yyyyMMdd} for date tags and {@code HHmmss±HHMM}
	 * for time tags) or, for repeatable tags such as Keywords, a collection or array of those.
	 * A null value or an empty collection removes the tag. Strings are stored as UTF-8 and the
	 * written block declares that.
	 *
	 * @throws IllegalArgumentException for an unknown tag or several values for a non-repeatable tag
	 * @throws UnsupportedOperationException if the source image format cannot hold IPTC
	 */
	public MetadataEditor setIptcTag(String name, Object value) {
		IPTCApplicationTag tag = TagNames.iptc(name);
		List<String> values = iptcStrings(tag, value);
		if(values.isEmpty()) return removeIptcTag(name);
		checkWritable(MetadataType.IPTC);
		if(values.size() > 1 && !tag.allowMultiple())
			throw new IllegalArgumentException("IPTC tag " + tag.getName() + " does not allow multiple values");
		if(tag == IPTCApplicationTag.RECORD_VERSION)
			throw new IllegalArgumentException("ApplicationRecordVersion is written by the library");
		if(iptc == null) iptc = new ArrayList<IPTCDataSet>();
		removeIptc(tag);
		for(String v : values) iptc.add(new IPTCDataSet(tag, v));
		iptcDirty = true;
		return this;
	}

	/** Sets several IPTC tags; see {@link #setIptcTag}. */
	public MetadataEditor setIptcTags(Map<String, ?> values) {
		for(Map.Entry<String, ?> e : values.entrySet()) setIptcTag(e.getKey(), e.getValue());
		return this;
	}

	/** Removes all data sets of an IPTC tag; a no-op if absent. */
	public MetadataEditor removeIptcTag(String name) {
		IPTCApplicationTag tag = TagNames.iptc(name);
		if(iptc != null && removeIptc(tag)) iptcDirty = true;
		return this;
	}

	private boolean removeIptc(IPTCApplicationTag tag) {
		boolean removed = false;
		for(Iterator<IPTCDataSet> it = iptc.iterator(); it.hasNext();) {
			IPTCDataSet d = it.next();
			if(isApplication(d) && d.getTag() == tag.getTag()) { it.remove(); removed = true; }
		}
		return removed;
	}

	private static List<String> iptcStrings(IPTCApplicationTag tag, Object value) {
		List<String> out = new ArrayList<String>();
		if(value == null) return out;
		if(value instanceof Collection || (value.getClass().isArray() && !(value instanceof byte[]))) {
			Object[] items = value instanceof Collection ? ((Collection<?>)value).toArray() : toObjectArray(value);
			for(Object o : items) {
				String s = iptcString(tag, o);
				if(s != null) out.add(s);
			}
			return out;
		}
		String s = iptcString(tag, value);
		if(s != null) out.add(s);
		return out;
	}

	private static Object[] toObjectArray(Object array) {
		int n = Array.getLength(array);
		Object[] out = new Object[n];
		for(int i = 0; i < n; i++) out[i] = Array.get(array, i);
		return out;
	}

	private static String iptcString(IPTCApplicationTag tag, Object value) {
		if(value == null) return null;
		if(value instanceof byte[]) return new String((byte[])value, StandardCharsets.UTF_8);
		boolean date = IPTC_DATE_TAGS.contains(tag), time = IPTC_TIME_TAGS.contains(tag);
		if((date || time) && (value instanceof Date || value instanceof Calendar || value instanceof TemporalAccessor)) {
			if(value instanceof Date) return new SimpleDateFormat(date ? "yyyyMMdd" : "HHmmssZ").format((Date)value);
			if(value instanceof Calendar) {
				SimpleDateFormat f = new SimpleDateFormat(date ? "yyyyMMdd" : "HHmmssZ");
				f.setTimeZone(((Calendar)value).getTimeZone());
				return f.format(((Calendar)value).getTime());
			}
			TemporalAccessor t = (TemporalAccessor)value;
			try {
				return DateTimeFormatter.ofPattern(date ? "yyyyMMdd" : "HHmmssxx").format(t);
			} catch (RuntimeException e) {
				if(time) {
					try {
						return DateTimeFormatter.ofPattern("HHmmss").format(t);
					} catch (RuntimeException e2) {
						throw new IllegalArgumentException("Cannot format " + value + " as an IPTC time", e2);
					}
				}
				throw new IllegalArgumentException("Cannot format " + value + " as an IPTC date", e);
			}
		}
		return value.toString();
	}

	// ---------------------------------------------------------------- XMP, ICC, comments

	/** The XMP packet as text, or null. */
	public String getXmp() {
		return xmp;
	}

	/**
	 * Sets the XMP packet (UTF-8 XML, with or without the {@code <?xpacket?>} wrapper, which is
	 * rewritten on output). Null removes it.
	 *
	 * @throws IllegalArgumentException if the text is not well-formed XML
	 */
	public MetadataEditor setXmp(String xmp) {
		if(xmp == null || xmp.trim().isEmpty()) {
			if(this.xmp != null || sourceTypes.contains(MetadataType.XMP)) { this.xmp = null; xmpDirty = true; }
			return this;
		}
		checkWritable(MetadataType.XMP);
		String stripped = xmp.replaceAll("<\\?xpacket[^>]*\\?>", "").trim();
		if(XMLUtils.createXML(stripped) == null)
			throw new IllegalArgumentException("XMP is not well-formed XML");
		this.xmp = stripped;
		xmpDirty = true;
		return this;
	}

	/** The ICC profile bytes, or null. */
	public byte[] getIccProfile() {
		return icc == null ? null : icc.clone();
	}

	/** Sets the ICC profile; null removes it. */
	public MetadataEditor setIccProfile(byte[] profile) {
		if(profile == null || profile.length == 0) {
			if(icc != null || sourceTypes.contains(MetadataType.ICC_PROFILE)) { icc = null; iccDirty = true; }
			return this;
		}
		checkWritable(MetadataType.ICC_PROFILE);
		icc = profile.clone();
		iccDirty = true;
		return this;
	}

	/** The comments (a copy), empty if there are none. */
	public List<String> getComments() {
		return comments == null ? new ArrayList<String>() : new ArrayList<String>(comments);
	}

	/** Replaces the comments; null or empty removes them. */
	public MetadataEditor setComments(List<String> comments) {
		if(comments == null || comments.isEmpty()) {
			if(this.comments != null || sourceTypes.contains(MetadataType.COMMENT)) { this.comments = null; commentsDirty = true; }
			return this;
		}
		checkWritable(MetadataType.COMMENT);
		this.comments = new ArrayList<String>(comments);
		commentsDirty = true;
		return this;
	}

	// ---------------------------------------------------------------- removal

	/**
	 * Removes whole metadata types. EXIF, IPTC, XMP, ICC_PROFILE and COMMENT clear the working
	 * copy; the others (PHOTOSHOP_IRB, PNG_TEXTUAL, JPG_ADOBE, ...) are removed from the file on
	 * apply where the format has them.
	 */
	public MetadataEditor remove(MetadataType... types) {
		for(MetadataType type : types) {
			switch(type) {
				case EXIF: if(exif != null || sourceTypes.contains(type)) { exif = null; exifDirty = true; } break;
				case IPTC: if(iptc != null || sourceTypes.contains(type)) { iptc = null; iptcDirty = true; } break;
				case XMP: setXmp(null); break;
				case ICC_PROFILE: setIccProfile(null); break;
				case COMMENT: setComments(null); break;
				default: removals.add(type);
			}
		}
		return this;
	}

	/** Removes every metadata type the source format can hold, including IRB and PNG text chunks. */
	public MetadataEditor removeAll() {
		Set<MetadataType> types = sourceType != null ? removableTypes(sourceType) : EnumSet.of(MetadataType.EXIF,
				MetadataType.IPTC, MetadataType.XMP, MetadataType.ICC_PROFILE, MetadataType.COMMENT, MetadataType.PHOTOSHOP_IRB);
		return remove(types.toArray(new MetadataType[0]));
	}

	// ---------------------------------------------------------------- apply

	/**
	 * Builds the {@link MetadataUpdate} that applying the editor to an image of the given format
	 * performs.
	 *
	 * @throws UnsupportedOperationException if a metadata type the editor holds cannot be written
	 *         to that format
	 */
	public MetadataUpdate toUpdate(ImageType target) {
		if(!isSupported(target)) throw new UnsupportedOperationException("Unsupported image format: " + target);
		Set<MetadataType> writable = writableTypes(target);
		Set<MetadataType> removable = removableTypes(target);
		MetadataUpdate update = new MetadataUpdate();
		if(exifDirty) {
			if(exif == null || isEmpty(exif)) { if(removable.contains(MetadataType.EXIF)) update.remove(MetadataType.EXIF); }
			else { require(writable, MetadataType.EXIF, target); update.exif(workingCopy(exif, false), false); }
		}
		if(iptcDirty) {
			if(iptc == null || iptc.isEmpty()) { if(removable.contains(MetadataType.IPTC)) update.remove(MetadataType.IPTC); }
			else {
				require(writable, MetadataType.IPTC, target);
				List<IPTCDataSet> out = new ArrayList<IPTCDataSet>(iptc);
				boolean hasVersion = false;
				for(IPTCDataSet d : out)
					hasVersion |= isApplication(d) && d.getTag() == IPTCApplicationTag.RECORD_VERSION.getTag();
				if(!hasVersion) out.add(new IPTCDataSet(IPTCApplicationTag.RECORD_VERSION.getTag(), new byte[] {0, 4}));
				update.iptc(out, false);
			}
		}
		if(xmpDirty) {
			if(xmp == null) { if(removable.contains(MetadataType.XMP)) update.remove(MetadataType.XMP); }
			else { require(writable, MetadataType.XMP, target); update.xmp(xmp); }
		}
		if(iccDirty) {
			if(icc == null) { if(removable.contains(MetadataType.ICC_PROFILE)) update.remove(MetadataType.ICC_PROFILE); }
			else { require(writable, MetadataType.ICC_PROFILE, target); update.iccProfile(icc); }
		}
		if(commentsDirty) {
			if(removable.contains(MetadataType.COMMENT)) update.remove(MetadataType.COMMENT);
			if(comments != null && !comments.isEmpty()) { require(writable, MetadataType.COMMENT, target); update.comments(comments); }
		}
		for(MetadataType type : removals)
			if(removable.contains(type)) update.remove(type);
		return update;
	}

	private static void require(Set<MetadataType> writable, MetadataType type, ImageType target) {
		if(!writable.contains(type))
			throw new UnsupportedOperationException(type + " cannot be written to a " + target + " image");
	}

	/**
	 * Applies the editor to an image: the source is copied to the destination with the metadata
	 * replaced by the editor's working copy. The target format is detected from the stream and may
	 * differ from the source format the editor was read from.
	 *
	 * @throws UnsupportedOperationException before anything is written if the editor holds a
	 *         metadata type the target format cannot carry
	 */
	public void apply(InputStream source, OutputStream destination) throws IOException {
		PeekHeadInputStream peek = new PeekHeadInputStream(source, Metadata.IMAGE_MAGIC_NUMBER_LEN, MetadataUtils.IMAGE_HEADER_PEEK_LEN);
		ImageType target = MetadataUtils.guessImageType(peek);
		MetadataUpdate update = toUpdate(target);
		Metadata.insertMetadata(peek, destination, update);
		peek.shallowClose();
	}

	/** Applies the editor to an in-memory image and returns the result. */
	public byte[] apply(byte[] image) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream(image.length + 8192);
		apply(new ByteArrayInputStream(image), out);
		return out.toByteArray();
	}

	/**
	 * Applies the editor to a file in place. The result is written to a temporary file next to it
	 * and moved over the original atomically where the file system supports it, so a failure
	 * leaves the original untouched.
	 */
	public void apply(Path file) throws IOException {
		Path dir = file.toAbsolutePath().getParent();
		Path tmp = Files.createTempFile(dir, "." + file.getFileName() + ".", ".tmp");
		try {
			try (InputStream in = Files.newInputStream(file); OutputStream out = Files.newOutputStream(tmp)) {
				apply(in, out);
			}
			try {
				Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			} catch (AtomicMoveNotSupportedException e) {
				Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
			}
		} finally {
			Files.deleteIfExists(tmp);
		}
	}

	/** Applies the editor to {@code source}, writing the result to {@code destination} (replaced if it exists). */
	public void apply(Path source, Path destination) throws IOException {
		if(source.toAbsolutePath().normalize().equals(destination.toAbsolutePath().normalize())) {
			apply(source);
			return;
		}
		Path dir = destination.toAbsolutePath().getParent();
		Path tmp = Files.createTempFile(dir, "." + destination.getFileName() + ".", ".tmp");
		try {
			try (InputStream in = Files.newInputStream(source); OutputStream out = Files.newOutputStream(tmp)) {
				apply(in, out);
			}
			try {
				Files.move(tmp, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			} catch (AtomicMoveNotSupportedException e) {
				Files.move(tmp, destination, StandardCopyOption.REPLACE_EXISTING);
			}
		} finally {
			Files.deleteIfExists(tmp);
		}
	}

	public void apply(File file) throws IOException {
		apply(file.toPath());
	}

	@Override
	public String toString() {
		return "MetadataEditor[source=" + sourceType + ", types=" + getTypes() + ", changed=" + hasChanges() + "]";
	}

	@SuppressWarnings("unused")
	private static List<Object> unused() { return Arrays.asList(); }
}
