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
 * GPSTag.java
 *
 * Who   Date       Description
 * ====  =======    =================================================
 * AK    09Nov2026  Added the EXIF 2.32 field type of every tag
 */

package pixy.meta.exif;

import java.text.DecimalFormat;
import java.util.HashMap;
import java.util.Map;

import pixy.image.tiff.FieldType;
import pixy.image.tiff.Tag;
import pixy.image.tiff.TiffTag;
import pixy.string.StringUtils;

/**
 * Defines GPS tags
 *
 * @author Wen Yu, yuwen_66@yahoo.com
 * @version 1.0 06/10/2013
 */
public enum GPSTag implements Tag {
	// EXIF GPSSubIFD tags
	GPS_VERSION_ID("GPSVersionID", (short)0x0000, FieldType.BYTE),
	GPS_LATITUDE_REF("GPSLatitudeRef", (short)0x0001, FieldType.ASCII),
	GPS_LATITUDE("GPSLatitude", (short)0x0002, FieldType.RATIONAL) {
		public String getFieldAsString(Object value) {
			return degreesMinutesSeconds("GPSLatitute", value);
		}
	},
	GPS_LONGITUDE_REF("GPSLongitudeRef", (short)0x0003, FieldType.ASCII),
	GPS_LONGITUDE("GPSLongitude", (short)0x0004, FieldType.RATIONAL) {
		public String getFieldAsString(Object value) {
			return degreesMinutesSeconds("GPSLongitude", value);
		}
	},
	GPS_ALTITUDE_REF("GPSAltitudeRef", (short)0x0005, FieldType.BYTE),
	GPS_ALTITUDE("GPSAltitude", (short)0x0006, FieldType.RATIONAL) {
		public String getFieldAsString(Object value) {
			return rationalWithUnit("GPSAltitute", value, "m");
		}
	},
	GPS_TIME_STAMP("GPSTimeStamp", (short)0x0007, FieldType.RATIONAL) {
		public String getFieldAsString(Object value) {
			int[] intValues = (int[])value;
			if(intValues.length != 6)
				throw new IllegalArgumentException("Wrong number of GPSTimeStamp data number: " + intValues.length);
			//formatting numbers up to 2 decimal places in Java
	        DecimalFormat df = new DecimalFormat("#,###,###.##");
	        return StringUtils.rationalToString(df, true, intValues[0], intValues[1]) + ":" + StringUtils.rationalToString(df, true, intValues[2], intValues[3])
	        		+ ":" + StringUtils.rationalToString(df, true, intValues[4], intValues[5]);
		}
	},
	GPS_SATELLITES("GPSSatellites", (short)0x0008, FieldType.ASCII),
	GPS_STATUS("GPSStatus", (short)0x0009, FieldType.ASCII),
	GPS_MEASURE_MODE("GPSMeasureMode", (short)0x000a, FieldType.ASCII),
	GPS_DOP("GPSDOP/ProcessingSoftware", (short)0x000b, FieldType.RATIONAL),
	GPS_SPEED_REF("GPSSpeedRef", (short)0x000c, FieldType.ASCII),
	GPSSpeed("GPSSpeed", (short)0x000d, FieldType.RATIONAL),
	GPS_TRACK_REF("GPSTrackRef", (short)0x000e, FieldType.ASCII),
	GPS_TRACK("GPSTrack", (short)0x000f, FieldType.RATIONAL),
	GPS_IMG_DIRECTION_REF("GPSImgDirectionRef", (short)0x0010, FieldType.ASCII),
	GPS_IMG_DIRECTION("GPSImgDirection", (short)0x0011, FieldType.RATIONAL) {
		public String getFieldAsString(Object value) {
			return rationalWithUnit("GPSImgDirection", value, "°");
		}
	},
	GPS_MAP_DATUM("GPSMapDatum", (short)0x0012, FieldType.ASCII),
	GPS_DEST_LATITUDE_REF("GPSDestLatitudeRef", (short)0x0013, FieldType.ASCII),
	GPS_DEST_LATITUDE("GPSDestLatitude", (short)0x0014, FieldType.RATIONAL) {
		public String getFieldAsString(Object value) {
			return degreesMinutesSeconds("GPSDestLatitute", value);
		}
	},
	GPS_DEST_LONGITUDE_REF("GPSDestLongitudeRef", (short)0x0015, FieldType.ASCII),
	GPS_DEST_LONGITUDE("GPSDestLongitude", (short)0x0016, FieldType.RATIONAL) {
		public String getFieldAsString(Object value) {
			return degreesMinutesSeconds("GPSDestLongitude", value);
		}
	},
	GPS_DEST_BEARING_REF("GPSDestBearingRef", (short)0x0017, FieldType.ASCII),
	GPS_DEST_BEARING("GPSDestBearing", (short)0x0018, FieldType.RATIONAL) {
		public String getFieldAsString(Object value) {
			return rationalWithUnit("GPSDestBearing", value, "°");
		}
	},
	GPS_DEST_DISTANCE_REF("GPSDestDistanceRef", (short)0x0019, FieldType.ASCII),
	GPS_DEST_DISTANCE("GPSDestDistance", (short)0x001a, FieldType.RATIONAL) {
		public String getFieldAsString(Object value) {
			return rationalWithUnit("GPSDestDistance", value, "");
		}
	},
	GPS_PROCESSING_METHOD("GPSProcessingMethod", (short)0x001b, FieldType.UNDEFINED),
	GPS_AREA_INFORMATION("GPSAreaInformation", (short)0x001c, FieldType.UNDEFINED),
	GPS_DATE_STAMP("GPSDateStamp", (short)0x001d, FieldType.ASCII),
	GPS_DIFFERENTIAL("GPSDifferential", (short)0x001e, FieldType.SHORT),
	GPS_HPOSITIONING_ERROR("GPSHPositioningError", (short)0x001f, FieldType.RATIONAL),
	// unknown tag
	UNKNOWN("Unknown",  (short)0xffff);
    // End of EXIF GPSSubIFD tags

	private GPSTag(String name, short value)
	{
		this(name, value, FieldType.UNKNOWN);
	}

	private GPSTag(String name, short value, FieldType fieldType)
	{
		this.name = name;
		this.value = value;
		this.fieldType = fieldType;
	}

	public String getName() {
		return name;
	}

	public short getValue() {
		return value;
	}

	@Override
    public String toString() {
		if (this == UNKNOWN)
			return name;
		return name + " [Value: " + StringUtils.shortToHexStringMM(value) +"]";
	}

    public static Tag fromShort(short value) {
       	GPSTag tag = tagMap.get(value);
    	if (tag == null)
    	   return TiffTag.UNKNOWN;
   		return tag;
    }

    private static final Map<Short, GPSTag> tagMap = new HashMap<Short, GPSTag>();

    static
    {
      for(GPSTag tag : values()) {
           tagMap.put(tag.getValue(), tag);
      }
    }

    private static String degreesMinutesSeconds(String what, Object value) {
		int[] intValues = (int[])value;
		if(intValues.length != 6)
			throw new IllegalArgumentException("Wrong number of " + what + " data number: " + intValues.length);
		//formatting numbers up to 3 decimal places in Java
        DecimalFormat df = new DecimalFormat("#,###,###.###");
        return StringUtils.rationalToString(df,  true, intValues[0], intValues[1]) + '°' + StringUtils.rationalToString(df, true, intValues[2], intValues[3])
        		+ "'" + StringUtils.rationalToString(df, true, intValues[4], intValues[5]) + "\"";
    }

    private static String rationalWithUnit(String what, Object value, String unit) {
		int[] intValues = (int[])value;
		if(intValues.length != 2)
			throw new IllegalArgumentException("Wrong number of " + what + " data number: " + intValues.length);
		//formatting numbers up to 3 decimal places in Java
        DecimalFormat df = new DecimalFormat("#,###,###.###");
        return StringUtils.rationalToString(df, true, intValues) + unit;
    }

    /**
     * Intended to be overridden by certain tags to provide meaningful string
     * representation of the field value such as compression, photo metric interpretation etc.
     *
	 * @param value field value to be mapped to a string
	 * @return a string representation of the field value or empty string if no meaningful string
	 * 	representation exists.
	 */
	public String getFieldAsString(Object value) {
    	return "";
	}

	public boolean isCritical() {
    	return true;
    }

    /**
     * The field type the EXIF specification (version 2.32) defines for this tag, or
     * {@link FieldType#UNKNOWN} for the UNKNOWN tag.
     */
	public FieldType getFieldType() {
		return fieldType;
	}

	private final String name;
	private final short value;
	private final FieldType fieldType;
}
