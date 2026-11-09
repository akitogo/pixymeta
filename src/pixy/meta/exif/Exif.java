/**
 * Copyright (C) 2014-2019 by Wen Yu.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Any modifications to this file must keep this entire header intact.
 *
 * Change History - most recent changes go on top of previous changes
 *
 * Exif.java
 *
 * Who   Date       Description
 * ====  =======    =================================================
 * AK    09Nov2026  Added addField(Tag, Object), getField(Tag) and removeField(Tag)
 * WY    10Apr2015  Moved data loaded checking to ExifReader
 * WY    31Mar2015  Fixed bug with getImageIFD() etc
 * WY    17Feb2015  Added addImageField() to add TIFF image tag
 * WY    11Feb2015  Added showMetadata()
 * WY    03Feb2015  Factored out TiffExif and JpegExif
 * WY    03Feb2015  Made class abstract
 * WY    14Jan2015  Moved thumbnail related code to ExifThumbnail
 * WY    06May2014  Complete rewrite to support adding thumbnail IFD
 */

package pixy.meta.exif;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import pixy.meta.Metadata;
import pixy.meta.MetadataEntry;
import pixy.meta.MetadataType;
import pixy.meta.Thumbnail;
import pixy.meta.exif.makernote.MakerNote;
import pixy.meta.tiff.TIFFMeta;
import pixy.string.StringUtils;
import pixy.image.tiff.FieldType;
import pixy.image.tiff.IFD;
import pixy.image.tiff.MakerNoteField;
import pixy.image.tiff.Tag;
import pixy.image.tiff.TiffField;
import pixy.image.tiff.TiffTag;
import pixy.io.FileCacheRandomAccessInputStream;
import pixy.io.FileCacheRandomAccessOutputStream;
import pixy.io.IOUtils;
import pixy.io.RandomAccessInputStream;
import pixy.io.RandomAccessOutputStream;

/**
 * EXIF wrapper
 *  
 * @author Wen Yu, yuwen_66@yahoo.com
 * @version 1.0 01/08/2014
 */
public abstract class Exif extends Metadata {
	protected IFD imageIFD;
	protected IFD exifSubIFD;
	protected IFD gpsSubIFD;
	protected IFD interopSubIFD;
	protected ExifThumbnail thumbnail;
	protected short preferredEndian = IOUtils.BIG_ENDIAN;
	
	private boolean containsThumbnail;
	private boolean isThumbnailRequired;
	
	public static final int FIRST_IFD_OFFSET = 0x08;

	/**
	 * Upper bound for an embedded EXIF thumbnail (bytes). The length comes from the file; any real
	 * thumbnail is well under this, so the cap blocks a memory-exhaustion vector without affecting
	 * valid images.
	 */
	private static final int MAX_THUMBNAIL_LENGTH = 64 * 1024 * 1024;
	
	// Obtain a logger instance
	private static final Logger LOGGER = LoggerFactory.getLogger(Exif.class);
	
	public Exif() {
		super(MetadataType.EXIF);
		isDataRead = true;
	}
	
	public Exif(byte[] data) {
		super(MetadataType.EXIF, data);
		ensureDataRead();
	}
	
	public Exif(IFD imageIFD) {
		this();
		setImageIFD(imageIFD);
	}
	
	public Exif(InputStream is) throws IOException {
		this(IOUtils.inputStreamToByteArray(is));
	}
	
	/**
	 * Adds a field to the EXIF SubIFD with an explicit field type. The value is
	 * {@link FieldType#coerce(Object) coerced} to the type's Java representation, so both
	 * {@code new short[] {400}} and {@code 400} are accepted for a SHORT.
	 */
	public void addExifField(ExifTag tag, FieldType type, Object data) {
		if(exifSubIFD == null)
			exifSubIFD = new IFD();
		exifSubIFD.addField(createField(tag, type, data, "EXIF"));
	}

	/** Adds a field to the GPS SubIFD with an explicit field type; see {@link #addExifField}. */
	public void addGPSField(GPSTag tag, FieldType type, Object data) {
		if(gpsSubIFD == null)
			gpsSubIFD = new IFD();
		gpsSubIFD.addField(createField(tag, type, data, "GPS"));
	}

	/** Adds a field to the Interoperability SubIFD with an explicit field type; see {@link #addExifField}. */
	public void addInteropField(InteropTag tag, FieldType type, Object data) {
		if(interopSubIFD == null)
			interopSubIFD = new IFD();
		interopSubIFD.addField(createField(tag, type, data, "interop"));
	}

	/** Adds a field to the image IFD (IFD0) with an explicit field type; see {@link #addExifField}. */
	public void addImageField(TiffTag tag, FieldType type, Object data) {
		if(imageIFD == null)
			imageIFD = new IFD();
		imageIFD.addField(createField(tag, type, data, "Image"));
	}

	private static TiffField<?> createField(Tag tag, FieldType type, Object data, String what) {
		if(type == null || type == FieldType.UNKNOWN)
			throw new IllegalArgumentException("A field type is required for " + what + " tag " + tag.getName());
		TiffField<?> field = FieldType.createField(tag, type, type.coerce(data));
		if(field == null)
			throw new IllegalArgumentException("Cannot create required " + what + " TIFF field " + tag.getName() + " of type " + type);
		return field;
	}

	/**
	 * Adds (or replaces) a field, choosing the IFD from the tag's class and the TIFF field type
	 * from {@link Tag#getFieldType()}: a {@link TiffTag} goes to the image IFD (IFD0), an
	 * {@link ExifTag} to the EXIF SubIFD, a {@link GPSTag} to the GPS SubIFD and an
	 * {@link InteropTag} to the Interoperability SubIFD.
	 * <p>
	 * The value may be given in any form {@link FieldType#coerce(Object)} accepts, for example
	 * <pre>
	 *   exif.addField(TiffTag.ARTIST, "Jane Tester");
	 *   exif.addField(ExifTag.DATE_TIME_ORIGINAL, new Date());
	 *   exif.addField(ExifTag.ISO_SPEED_RATINGS, 400);
	 *   exif.addField(ExifTag.FNUMBER, 2.8);
	 *   exif.addField(ExifTag.EXPOSURE_TIME, "1/250");
	 *   exif.addField(GPSTag.GPS_LATITUDE, new double[] {52, 30, 12.5});
	 *   exif.addField(ExifTag.USER_COMMENT, "Hello");   // stored with the ASCII/UNICODE character code
	 * </pre>
	 * UserComment, GPSProcessingMethod and GPSAreaInformation take a string and prepend the
	 * 8 byte character code the specification requires ({@code ASCII} if the text is 7-bit
	 * ASCII, otherwise {@code UNICODE} with UTF-16 in this EXIF's byte order).
	 *
	 * @param tag the tag; its {@link Tag#getFieldType() field type} must be known
	 * @param value the value
	 * @throws IllegalArgumentException if the tag's type is unknown, the tag class is not one of
	 *         the four above or the value cannot be converted
	 */
	public void addField(Tag tag, Object value) {
		if(tag == null) throw new IllegalArgumentException("Tag is null");
		if(value == null) throw new IllegalArgumentException("Value for " + tag.getName() + " is null");
		FieldType type = tag.getFieldType();
		if(type == null || type == FieldType.UNKNOWN)
			throw new IllegalArgumentException("No field type is defined for tag " + tag.getName()
					+ "; use addImageField/addExifField/addGPSField/addInteropField with an explicit FieldType");
		if(value instanceof String && (tag == ExifTag.USER_COMMENT || tag == GPSTag.GPS_PROCESSING_METHOD
				|| tag == GPSTag.GPS_AREA_INFORMATION))
			value = encodeWithCharacterCode((String)value);
		if(tag instanceof TiffTag) addImageField((TiffTag)tag, type, value);
		else if(tag instanceof ExifTag) addExifField((ExifTag)tag, type, value);
		else if(tag instanceof GPSTag) addGPSField((GPSTag)tag, type, value);
		else if(tag instanceof InteropTag) addInteropField((InteropTag)tag, type, value);
		else throw new IllegalArgumentException("Unsupported tag class " + tag.getClass().getName() + " for tag " + tag.getName());
	}

	private static final byte[] CHARACTER_CODE_ASCII = {0x41, 0x53, 0x43, 0x49, 0x49, 0, 0, 0};
	private static final byte[] CHARACTER_CODE_UNICODE = {0x55, 0x4E, 0x49, 0x43, 0x4F, 0x44, 0x45, 0};

	/** UserComment style value: 8 byte character code followed by the text in that encoding. */
	private byte[] encodeWithCharacterCode(String text) {
		boolean ascii = true;
		for(int i = 0; i < text.length() && ascii; i++)
			ascii = text.charAt(i) < 0x80;
		byte[] code = ascii ? CHARACTER_CODE_ASCII : CHARACTER_CODE_UNICODE;
		byte[] body = text.getBytes(ascii ? StandardCharsets.US_ASCII
				: (preferredEndian == IOUtils.BIG_ENDIAN ? StandardCharsets.UTF_16BE : StandardCharsets.UTF_16LE));
		byte[] out = new byte[code.length + body.length];
		System.arraycopy(code, 0, out, 0, code.length);
		System.arraycopy(body, 0, out, code.length, body.length);
		return out;
	}

	/**
	 * Returns the field for a tag from the IFD its class belongs to (see {@link #addField}), or
	 * null if there is none.
	 */
	public TiffField<?> getField(Tag tag) {
		ensureDataRead();
		IFD ifd = ifdFor(tag);
		return ifd == null ? null : ifd.getField(tag);
	}

	/**
	 * Removes the field for a tag from the IFD its class belongs to (see {@link #addField}).
	 *
	 * @return the removed field, or null if there was none
	 */
	public TiffField<?> removeField(Tag tag) {
		ensureDataRead();
		IFD ifd = ifdFor(tag);
		return ifd == null ? null : ifd.removeField(tag);
	}

	private IFD ifdFor(Tag tag) {
		if(tag instanceof TiffTag) return imageIFD;
		if(tag instanceof ExifTag) return exifSubIFD;
		if(tag instanceof GPSTag) return gpsSubIFD;
		if(tag instanceof InteropTag) return interopSubIFD;
		throw new IllegalArgumentException("Unsupported tag class " + tag.getClass().getName());
	}
	
	public boolean containsThumbnail() {
		if(containsThumbnail)
			return true;
		if(thumbnail != null)
			return true;
		return false;
	}
	
	public IFD getExifIFD() {
		if(exifSubIFD != null) {
			return new IFD(exifSubIFD);
		}
		
		return null;
	}
	
	public IFD getGPSIFD() {
		if(gpsSubIFD != null) {
			return new IFD(gpsSubIFD);
		} 

		return null;
	}
	
	public IFD getInteropIFD() {
		if(interopSubIFD != null) {
			return new IFD(interopSubIFD);
		} 

		return null;
	}
	
	public IFD getImageIFD() {
		if(imageIFD != null) {
			return new IFD(imageIFD);
		}
		
		return null;		
	}
	
	private void getMetadataEntries(IFD currIFD, Class<? extends Tag> tagClass, List<MetadataEntry> items) {
		// Use reflection to invoke fromShort(short) method
		Method method = null;
		try {
			method = tagClass.getDeclaredMethod("fromShort", short.class);
		} catch (NoSuchMethodException e) {
			throw new RuntimeException("Method 'fromShort' is not defined for class " + tagClass);
		} catch (SecurityException e) {
			throw new RuntimeException("The operation is not allowed by the current security setup");
		}
		
		Collection<TiffField<?>> fields = currIFD.getFields();
		MetadataEntry entry = null;
		
		if(tagClass.equals(TiffTag.class)) {
			entry = new MetadataEntry("IFD0", "Image Info", true);
		} else if(tagClass.equals(ExifTag.class)) {
			entry = new MetadataEntry("EXIF SubIFD", "EXIF Info", true);
		} else if(tagClass.equals(GPSTag.class)) {
			entry = new MetadataEntry("GPS SubIFD", "GPS Info", true);
		} else if(tagClass.equals(InteropTag.class)) {
			entry = new MetadataEntry("Interoperability SubIFD", "Interoperability Info", true);
		} else
			entry = new MetadataEntry("UNKNOWN", "UNKNOWN SubIFD", true);
		
		for(TiffField<?> field : fields) {
			short tag = field.getTag();
			Tag ftag = TiffTag.UNKNOWN;
			if(tag == ExifTag.PADDING.getValue()) {
				ftag = ExifTag.PADDING;
			} else if(tag == ExifTag.EXIF_INTEROPERABILITY_OFFSET.getValue()) {
				ftag = ExifTag.EXIF_INTEROPERABILITY_OFFSET;
			} else {
				try {
					ftag = (Tag)method.invoke(null, tag);
				} catch (IllegalAccessException e) {
					throw new RuntimeException("Illegal access for method: " + method);
				} catch (IllegalArgumentException e) {
					throw new RuntimeException("Illegal argument for method:  " + method);
				} catch (InvocationTargetException e) {
					throw new RuntimeException("Incorrect invocation target");
				}
			}	
			if (ftag == TiffTag.UNKNOWN)
				LOGGER.warn("Tag: {} [Value: 0x{}] (Unknown)", ftag, Integer.toHexString(tag&0xffff));
			
			FieldType ftype = field.getType();				
						
			String tagString = null;
			try {
				if(ftype == FieldType.SHORT || ftype == FieldType.SSHORT)
					tagString = ftag.getFieldAsString(field.getDataAsLong());
				else
					tagString = ftag.getFieldAsString(field.getData());
			} catch (Exception e) {
				// A per-tag formatter can throw on a malformed field (wrong element count, a zero
				// denominator in a rational, a type mismatch). Fall back to the raw string value so
				// one bad field does not abort iterating the whole EXIF block.
				LOGGER.warn("Falling back to raw value for tag {}: {}", ftag.getName(), e.toString());
				tagString = null;
			}
			if(StringUtils.isNullOrEmpty(tagString))
				entry.addEntry(new MetadataEntry(ftag.getName(), field.getDataAsString()));
			else
				entry.addEntry(new MetadataEntry(ftag.getName(), tagString));
		}
		
		items.add(entry); // Add the Entry (group) into the collection
		
		if(tagClass.equals(ExifTag.class)) {
			// Decoded vendor maker note directories follow the EXIF SubIFD they live in
			MakerNote makerNote = getMakerNote(currIFD);
			if(makerNote != null)
				items.addAll(makerNote.getMetadataEntries());
		}
		
		Map<Tag, IFD> children = currIFD.getChildren();
		
		if(children.get(TiffTag.EXIF_SUB_IFD) != null) {
			getMetadataEntries(children.get(TiffTag.EXIF_SUB_IFD), ExifTag.class, items);
		}
		
		if(children.get(ExifTag.EXIF_INTEROPERABILITY_OFFSET) != null) {
			getMetadataEntries(children.get(ExifTag.EXIF_INTEROPERABILITY_OFFSET), InteropTag.class, items);
		}
		
		if(children.get(TiffTag.GPS_SUB_IFD) != null) {
			getMetadataEntries(children.get(TiffTag.GPS_SUB_IFD), GPSTag.class, items);
		}		
	}

	/**
	 * Decodes the vendor specific MakerNote (0x927c) of the EXIF SubIFD, if present and in a
	 * supported format (Canon, Nikon, Sony, Olympus, Fujifilm, Panasonic, Pentax, Apple and others).
	 * 
	 * @return the decoded maker note, or null if there is none or its format is not supported
	 */
	public MakerNote getMakerNote() {
		ensureDataRead();
		return (exifSubIFD == null) ? null : getMakerNote(exifSubIFD);
	}
	
	private static MakerNote getMakerNote(IFD ifd) {
		TiffField<?> field = ifd.getField(ExifTag.MAKER_NOTE);
		if(field instanceof MakerNoteField)
			return ((MakerNoteField)field).getMakerNote();
		return null;
	}
	
	public ExifThumbnail getThumbnail() {
		if(thumbnail != null)
			return new ExifThumbnail(thumbnail);
	
		return null;
	}
	
	public boolean isThumbnailRequired() {
		return isThumbnailRequired;
	}
	
	public short getPreferredEndian() {
		return preferredEndian;
	}
	
	public Iterator<MetadataEntry> iterator() {
		ensureDataRead();
		List<MetadataEntry> items = new ArrayList<MetadataEntry>();
		if(imageIFD != null)
			getMetadataEntries(imageIFD, TiffTag.class, items);
		if(containsThumbnail) {
			MetadataEntry thumbnailEntry = new MetadataEntry("IFD1", "Thumbnail Image", true);
			thumbnailEntry.addEntry(new MetadataEntry("Thumbnail format", (thumbnail.getDataType() == 1? "DATA_TYPE_KJpegRGB":"DATA_TYPE_TIFF")));
			thumbnailEntry.addEntry(new MetadataEntry("Thumbnail data length", "" + thumbnail.getCompressedImage().length));
			items.add(thumbnailEntry);
		}
	
		return Collections.unmodifiableList(items).iterator();
	}
	
	public void read() throws IOException {
		if(!isDataRead) {
			RandomAccessInputStream exifIn = new FileCacheRandomAccessInputStream(new ByteArrayInputStream(data));
			List<IFD> ifds = new ArrayList<IFD>(3);
			TIFFMeta.readIFDs(ifds, exifIn);

			preferredEndian = exifIn.getEndian();
			
			if(ifds.size() > 0) {
				imageIFD = ifds.get(0);
				exifSubIFD = imageIFD.getChild(TiffTag.EXIF_SUB_IFD);
				if(exifSubIFD != null)
					interopSubIFD = exifSubIFD.getChild(ExifTag.EXIF_INTEROPERABILITY_OFFSET);
				gpsSubIFD = imageIFD.getChild(TiffTag.GPS_SUB_IFD);
			}
		    // We have thumbnail IFD
		    if(ifds.size() >= 2) {
		    	IFD thumbnailIFD = ifds.get(1);
		    	int width = -1;
		    	int height = -1;
		    	TiffField<?> field = thumbnailIFD.getField(TiffTag.IMAGE_WIDTH);
		    	if(field != null) 
		    		width = field.getDataAsLong()[0];
		    	field = thumbnailIFD.getField(TiffTag.IMAGE_LENGTH);
		    	if(field != null)
		    		height = field.getDataAsLong()[0];
		    	field = thumbnailIFD.getField(TiffTag.JPEG_INTERCHANGE_FORMAT);
		    	TiffField<?> lengthField = thumbnailIFD.getField(TiffTag.JPEG_INTERCHANGE_FORMAT_LENGTH);
		    	if(field != null && lengthField != null) { // JPEG format, save as JPEG
		    		int thumbnailOffset = field.getDataAsLong()[0];
		    		// The length field may be absent (guarded above) and its value is file-supplied, so
		    		// reject a negative or oversized length before new byte[thumbnailLen] turns it into a
		    		// NegativeArraySizeException or OutOfMemoryError.
		    		int thumbnailLen = lengthField.getDataAsLong()[0];
		    		if(thumbnailLen < 0 || thumbnailLen > MAX_THUMBNAIL_LENGTH)
		    			throw new IOException("Invalid EXIF thumbnail length: " + thumbnailLen);
		    		exifIn.seek(thumbnailOffset);
		    		byte[] thumbnailData = new byte[thumbnailLen];
		    		exifIn.readFully(thumbnailData);
		    		thumbnail = new ExifThumbnail(width, height, Thumbnail.DATA_TYPE_KJpegRGB, thumbnailData, thumbnailIFD);
		    		containsThumbnail = true;
		    	} else if(field == null) { // Uncompressed TIFF
		    		field = thumbnailIFD.getField(TiffTag.STRIP_OFFSETS);
		    		if(field == null) 
		    			field = thumbnailIFD.getField(TiffTag.TILE_OFFSETS);
		    		if(field != null) {
		    			 exifIn.seek(0);
		    			 ByteArrayOutputStream bout = new ByteArrayOutputStream();
		    			 RandomAccessOutputStream tiffout = new FileCacheRandomAccessOutputStream(bout);
		    			 TIFFMeta.retainPages(exifIn, tiffout, 1);
		    			 tiffout.close(); // Auto flush when closed
		    			 thumbnail = new ExifThumbnail(width, height, Thumbnail.DATA_TYPE_TIFF, bout.toByteArray(), thumbnailIFD);
		    			 containsThumbnail = true;		    			    
		    		}
		    	}
		    }
		    exifIn.close();		
		    isDataRead = true;
		}
	}
	
	public void setExifIFD(IFD exifSubIFD) {
		this.exifSubIFD = exifSubIFD;
	}
	
	public void setGPSIFD(IFD gpsSubIFD) {
		this.gpsSubIFD = gpsSubIFD;
	}
	
	public void setInteropIFD(IFD interopSubIFD) {
		this.interopSubIFD = interopSubIFD;
	}
	
	public void setImageIFD(IFD imageIFD) {
		if(imageIFD == null)
			throw new IllegalArgumentException("Input image IFD is null");
		this.imageIFD = imageIFD;
		this.exifSubIFD = imageIFD.getChild(TiffTag.EXIF_SUB_IFD);
		this.gpsSubIFD = imageIFD.getChild(TiffTag.GPS_SUB_IFD);
	}
	
	/**
	 * @param thumbnail a Thumbnail instance. If null, a thumbnail
	 *        will be generated from the input image.
	 */	
	public void setThumbnail(ExifThumbnail thumbnail) {
		this.thumbnail = thumbnail;
	}
	
	public void setThumbnailImage(BufferedImage thumbnail) {
		if(this.thumbnail == null)
			this.thumbnail = new ExifThumbnail(); 
		this.thumbnail.setImage(thumbnail);
	}
	
	public void setThumbnailRequired(boolean isThumbnailRequired) {
		this.isThumbnailRequired = isThumbnailRequired;
	}
	
	public void setPreferredEndian(short preferredEndian) {
		if(preferredEndian != IOUtils.BIG_ENDIAN && preferredEndian != IOUtils.LITTLE_ENDIAN)
			throw new IllegalArgumentException("Invalid Exif endian!");
		this.preferredEndian = preferredEndian;
	}
		
	public abstract void write(OutputStream os) throws IOException;
}
