/**
 * Copyright (C) 2014-2019 by Wen Yu.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Any modifications to this file must keep this entire header intact.
 */

package pixy.image.tiff;

import java.io.UnsupportedEncodingException;
import java.lang.reflect.Array;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAccessor;
import java.util.Calendar;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * TIFF field type.
 * <p>
 * Besides naming the on-disk types, this enum knows which Java representation each type is
 * stored in ({@code String} for ASCII, {@code short[]} for SHORT, {@code int[]} pairs for
 * RATIONAL, ...) and can {@link #coerce(Object) coerce} friendlier inputs (a {@code Number}, a
 * {@code Date}, a decimal string, a {@code List}) into that representation.
 *
 * @author Wen Yu, yuwen_66@yahoo.com
 * @version 1.0 01/06/2013
 */
public enum FieldType {
	BYTE("Byte", (short)0x0001),
	ASCII("ASCII", (short)0x0002),
	SHORT("Short", (short)0x0003),
	LONG("Long", (short)0x0004),
	RATIONAL("Rational", (short)0x0005),
	SBYTE("SByte", (short)0x0006),
	UNDEFINED("Undefined", (short)0x0007),
	SSHORT("SShort", (short)0x0008),
	SLONG("SLong", (short)0x0009),
	SRATIONAL("SRational", (short)0x000a),
	FLOAT("Float", (short)0x000b),
	DOUBLE("Double", (short)0x000c),
	IFD("IFD", (short)0x000d),
	// There two are not actually TIFF defined field type, internally they TIFF BYTE fields
	WINDOWSXP("WindowsXP", (short)0x000e),
	EXIF_MAKERNOTE("ExifMakernote", (short)0x000f),
	
	UNKNOWN("Unknown", (short)0x0000);
	
	private FieldType(String name, short value) {
		this.name = name;
		this.value = value;
	}
	
	public static TiffField<?> createField(Tag tag, FieldType type, Object data) {
		if(data == null) throw new IllegalArgumentException("Input data is null");
    	TiffField<?> retValue = null;
    	Class<?> typeClass = data.getClass();
    	switch(type) {
    		case ASCII:
    			if(typeClass == String.class) {
    				retValue = new ASCIIField(tag.getValue(), (String)data);    				
    			}
    			break;
    		case BYTE:
    		case SBYTE:
    		case UNDEFINED:
    			if(typeClass == byte[].class) {
    				byte[] byteData = (byte[])data;
    				if(byteData.length > 0) {
    					if(type == FieldType.BYTE)
    						retValue = new ByteField(tag.getValue(), byteData);
    					else if(type == FieldType.SBYTE)
    						retValue = new SByteField(tag.getValue(), byteData);
    					else
    						retValue = new UndefinedField(tag.getValue(), byteData);
    				}
    			}
    			break;
    		case SHORT:
    		case SSHORT:
    			if(typeClass == short[].class) {
    				short[] shortData = (short[])data;
    				if(shortData.length > 0) {
    					if(type == FieldType.SHORT)
    						retValue = new ShortField(tag.getValue(), shortData);    
    					else
    						retValue = new SShortField(tag.getValue(), shortData); 
    				}
    			}
    			break;
    		case LONG:
    		case SLONG:
    			if(typeClass == int[].class) {
    				int[] intData = (int[])data;
    				if(intData.length > 0) {
    					if(type == FieldType.LONG)
    						retValue = new LongField(tag.getValue(), intData);
    					else
    						retValue = new SLongField(tag.getValue(), intData);
    				}
    			}
    			break;
    		case RATIONAL:
    		case SRATIONAL:
    			if(typeClass == int[].class) {
    				int[] intData = (int[])data;
    				if(intData.length > 0 && intData.length % 2 == 0) {
    					if(type == FieldType.RATIONAL)
    						retValue = new RationalField(tag.getValue(), intData);
    					else
    						retValue = new SRationalField(tag.getValue(), intData);
    				}
    			}
    			break;
    		case FLOAT:
    			if(typeClass == float[].class && ((float[])data).length > 0)
    				retValue = new FloatField(tag.getValue(), (float[])data);
    			break;
    		case DOUBLE:
    			if(typeClass == double[].class && ((double[])data).length > 0)
    				retValue = new DoubleField(tag.getValue(), (double[])data);
    			break;
    		case WINDOWSXP: // Not a real TIFF field type, just a convenient way to add Windows XP field as a sting
    			if(typeClass == String.class) {
    				try {
    					byte[] xp = (((String)data).trim() +'\0').getBytes("UTF-16LE");
						retValue = new ByteField(tag.getValue(), xp);
					} catch (UnsupportedEncodingException e) {
						e.printStackTrace();
					}
    			} else if(typeClass == byte[].class && ((byte[])data).length > 0) {
    				retValue = new ByteField(tag.getValue(), (byte[])data); // Already UTF-16LE encoded
    			}
    			break;
    		case EXIF_MAKERNOTE:
    			throw new UnsupportedOperationException("Creating maker note is not supported.");
    		default:
    	}

		return retValue;
	}

	/**
	 * Creates a field for a tag whose {@link Tag#getFieldType() field type} is known, coercing the
	 * value with {@link #coerce(Object)} first. So {@code createField(ExifTag.FNUMBER, 2.8)},
	 * {@code createField(ExifTag.ISO_SPEED_RATINGS, 400)} and
	 * {@code createField(ExifTag.DATE_TIME_ORIGINAL, new Date())} all work without the caller
	 * knowing the TIFF type or the Java array type behind it.
	 *
	 * @param tag the tag; its field type must not be {@link #UNKNOWN}
	 * @param value the value in any representation {@link #coerce(Object)} accepts
	 * @return the new field
	 * @throws IllegalArgumentException if the tag has no known field type or the value cannot be
	 *         converted to it
	 */
	public static TiffField<?> createField(Tag tag, Object value) {
		if(tag == null) throw new IllegalArgumentException("Tag is null");
		FieldType type = tag.getFieldType();
		if(type == null || type == UNKNOWN)
			throw new IllegalArgumentException("No field type is defined for tag " + tag.getName()
					+ "; use createField(Tag, FieldType, Object) and choose the type explicitly");
		TiffField<?> field = createField(tag, type, type.coerce(value));
		if(field == null)
			throw new IllegalArgumentException("Cannot create " + type + " field " + tag.getName() + " from " + describe(value));
		return field;
	}

	/**
	 * Converts a value into the Java representation {@link #createField(Tag, FieldType, Object)}
	 * expects for this field type. Values that already have the right representation are returned
	 * unchanged. Accepted inputs:
	 * <ul>
	 * <li>ASCII: {@code String}; {@code Date}, {@code Calendar} or a {@code java.time} date-time
	 *     (formatted as {@code yyyy:MM:dd HH:mm:ss}, the EXIF date format); any other object via
	 *     {@code toString()}.</li>
	 * <li>SHORT, SSHORT, LONG, SLONG, BYTE, SBYTE: a {@code Number}, a primitive array,
	 *     a {@code Number[]} or {@code Collection} of numbers, or a string of decimal numbers
	 *     separated by spaces or commas. Values must fit the type's range.</li>
	 * <li>RATIONAL, SRATIONAL: an {@code int[]} or {@code long[]} of numerator/denominator pairs
	 *     (PIXYMETA's native layout); a {@code Number} (integral values become {@code n/1}, other
	 *     values are approximated by continued fractions, e.g. 2.8 to 14/5); a {@code double[]},
	 *     {@code float[]}, {@code Number[]} or {@code Collection}, one rational per element; or a
	 *     string of such values, each written as {@code "2.8"} or {@code "28/10"}, separated by
	 *     spaces or commas (e.g. {@code "52/1 30/1 12.5"} for GPS coordinates).</li>
	 * <li>FLOAT, DOUBLE: a {@code Number}, primitive array, {@code Number[]}, {@code Collection}
	 *     or decimal string as above.</li>
	 * <li>UNDEFINED: {@code byte[]}; a {@code String} is stored as its UTF-8 bytes (so
	 *     {@code "0232"} works for ExifVersion); numbers as for BYTE.</li>
	 * <li>WINDOWSXP: {@code String} (encoded as UTF-16LE by {@code createField}) or an already
	 *     encoded {@code byte[]}.</li>
	 * </ul>
	 *
	 * @param value the value to convert, never null
	 * @return the converted value
	 * @throws IllegalArgumentException if the value cannot be converted
	 */
	public Object coerce(Object value) {
		if(value == null) throw new IllegalArgumentException("Input data is null");
		switch(this) {
			case ASCII:
				return toAscii(value);
			case WINDOWSXP:
				if(value instanceof String || value instanceof byte[]) return value;
				return toAscii(value);
			case BYTE:
			case SBYTE:
			case UNDEFINED:
				if(value instanceof byte[]) return value;
				if(value instanceof String) return ((String)value).getBytes(StandardCharsets.UTF_8);
				return toBytes(toLongs(value), this == SBYTE ? Byte.MIN_VALUE : 0, this == SBYTE ? Byte.MAX_VALUE : 0xff);
			case SHORT:
			case SSHORT:
				if(value instanceof short[]) return value;
				return toShorts(toLongs(value), this == SSHORT ? Short.MIN_VALUE : 0, this == SSHORT ? Short.MAX_VALUE : 0xffff);
			case LONG:
			case SLONG:
			case IFD:
				if(value instanceof int[]) return value;
				return toInts(toLongs(value), this == SLONG ? Integer.MIN_VALUE : 0, this == SLONG ? Integer.MAX_VALUE : 0xffffffffL);
			case RATIONAL:
			case SRATIONAL:
				return toRationals(value, this == SRATIONAL);
			case FLOAT:
				if(value instanceof float[]) return value;
				double[] f = toDoubles(value);
				float[] floats = new float[f.length];
				for(int i = 0; i < f.length; i++) floats[i] = (float)f[i];
				return floats;
			case DOUBLE:
				if(value instanceof double[]) return value;
				return toDoubles(value);
			default:
				return value;
		}
	}

	/** EXIF date-time format, also used by TIFF DateTime. */
	public static final String EXIF_DATE_FORMAT = "yyyy:MM:dd HH:mm:ss";

	private static String toAscii(Object value) {
		if(value instanceof String) return (String)value;
		if(value instanceof Date) return new SimpleDateFormat(EXIF_DATE_FORMAT).format((Date)value);
		if(value instanceof Calendar) {
			SimpleDateFormat format = new SimpleDateFormat(EXIF_DATE_FORMAT);
			format.setTimeZone(((Calendar)value).getTimeZone());
			return format.format(((Calendar)value).getTime());
		}
		if(value instanceof TemporalAccessor) {
			try {
				return DateTimeFormatter.ofPattern(EXIF_DATE_FORMAT).format((TemporalAccessor)value);
			} catch (RuntimeException e) {
				throw new IllegalArgumentException("Cannot format " + value + " as an EXIF date-time: " + e.getMessage(), e);
			}
		}
		if(value instanceof byte[]) return new String((byte[])value, StandardCharsets.UTF_8);
		if(value.getClass().isArray() || value instanceof Collection) {
			StringBuilder sb = new StringBuilder();
			for(Object o : elements(value)) {
				if(sb.length() > 0) sb.append(' ');
				sb.append(o);
			}
			return sb.toString();
		}
		return value.toString();
	}

	private static Object[] elements(Object value) {
		if(value instanceof Collection) return ((Collection<?>)value).toArray();
		if(value.getClass().isArray()) {
			int n = Array.getLength(value);
			Object[] out = new Object[n];
			for(int i = 0; i < n; i++) out[i] = Array.get(value, i);
			return out;
		}
		return new Object[] {value};
	}

	private static String[] tokens(String s) {
		String trimmed = s.trim();
		if(trimmed.isEmpty()) throw new IllegalArgumentException("Empty numeric string");
		return trimmed.split("[\\s,;]+");
	}

	private static long[] toLongs(Object value) {
		if(value instanceof String) {
			String[] parts = tokens((String)value);
			long[] out = new long[parts.length];
			for(int i = 0; i < parts.length; i++) {
				try {
					out[i] = Long.parseLong(parts[i].trim());
				} catch (NumberFormatException e) {
					throw new IllegalArgumentException("Not an integer: '" + parts[i] + "'");
				}
			}
			return out;
		}
		Object[] items = elements(value);
		if(items.length == 0) throw new IllegalArgumentException("Empty value");
		long[] out = new long[items.length];
		for(int i = 0; i < items.length; i++) {
			Object o = items[i];
			if(o instanceof Number) {
				Number n = (Number)o;
				if(o instanceof Double || o instanceof Float) {
					double d = n.doubleValue();
					if(d != Math.rint(d)) throw new IllegalArgumentException("Not an integer: " + d);
				}
				out[i] = n.longValue();
			} else if(o instanceof Boolean) {
				out[i] = ((Boolean)o) ? 1 : 0;
			} else if(o instanceof String) {
				out[i] = toLongs(o)[0];
			} else
				throw new IllegalArgumentException("Cannot convert " + describe(o) + " to an integer");
		}
		return out;
	}

	private static void checkRange(long v, long min, long max) {
		if(v < min || v > max)
			throw new IllegalArgumentException("Value " + v + " is outside the range " + min + ".." + max + " of the field type");
	}

	private static byte[] toBytes(long[] longs, long min, long max) {
		byte[] out = new byte[longs.length];
		for(int i = 0; i < longs.length; i++) {
			checkRange(longs[i], min, max);
			out[i] = (byte)longs[i];
		}
		return out;
	}

	private static short[] toShorts(long[] longs, long min, long max) {
		short[] out = new short[longs.length];
		for(int i = 0; i < longs.length; i++) {
			checkRange(longs[i], min, max);
			out[i] = (short)longs[i];
		}
		return out;
	}

	private static int[] toInts(long[] longs, long min, long max) {
		int[] out = new int[longs.length];
		for(int i = 0; i < longs.length; i++) {
			checkRange(longs[i], min, max);
			out[i] = (int)longs[i];
		}
		return out;
	}

	private static double[] toDoubles(Object value) {
		if(value instanceof String) {
			String[] parts = tokens((String)value);
			double[] out = new double[parts.length];
			for(int i = 0; i < parts.length; i++) {
				try {
					out[i] = Double.parseDouble(parts[i].trim());
				} catch (NumberFormatException e) {
					throw new IllegalArgumentException("Not a number: '" + parts[i] + "'");
				}
			}
			return out;
		}
		Object[] items = elements(value);
		if(items.length == 0) throw new IllegalArgumentException("Empty value");
		double[] out = new double[items.length];
		for(int i = 0; i < items.length; i++) {
			Object o = items[i];
			if(o instanceof Number) out[i] = ((Number)o).doubleValue();
			else if(o instanceof String) out[i] = toDoubles(o)[0];
			else throw new IllegalArgumentException("Cannot convert " + describe(o) + " to a number");
		}
		return out;
	}

	private static int[] toRationals(Object value, boolean signed) {
		long min = signed ? Integer.MIN_VALUE : 0;
		long max = signed ? Integer.MAX_VALUE : 0xffffffffL;
		if(value instanceof int[]) {
			if(((int[])value).length % 2 != 0)
				throw new IllegalArgumentException("A rational int[] needs numerator/denominator pairs, got " + ((int[])value).length + " values");
			return (int[])value;
		}
		if(value instanceof long[]) {
			long[] longs = (long[])value;
			if(longs.length % 2 != 0)
				throw new IllegalArgumentException("A rational long[] needs numerator/denominator pairs, got " + longs.length + " values");
			return toInts(longs, min, max);
		}
		Object[] items = value instanceof String ? tokens((String)value) : elements(value);
		if(items.length == 0) throw new IllegalArgumentException("Empty value");
		int[] out = new int[items.length * 2];
		for(int i = 0; i < items.length; i++) {
			long[] r = toRational(items[i], signed);
			checkRange(r[0], min, max);
			checkRange(r[1], min, max);
			out[2 * i] = (int)r[0];
			out[2 * i + 1] = (int)r[1];
		}
		return out;
	}

	/** One rational from a number or a string like "2.8", "28/10" or "1/250". */
	private static long[] toRational(Object o, boolean signed) {
		if(o instanceof String) {
			String s = ((String)o).trim();
			int slash = s.indexOf('/');
			if(slash > 0) {
				try {
					return new long[] {Long.parseLong(s.substring(0, slash).trim()), Long.parseLong(s.substring(slash + 1).trim())};
				} catch (NumberFormatException e) {
					throw new IllegalArgumentException("Not a rational: '" + s + "'");
				}
			}
			try {
				return toRational(Double.parseDouble(s), signed);
			} catch (NumberFormatException e) {
				throw new IllegalArgumentException("Not a number: '" + s + "'");
			}
		}
		if(o instanceof Number) {
			if(o instanceof Double || o instanceof Float || o instanceof java.math.BigDecimal)
				return toRational(((Number)o).doubleValue(), signed);
			return new long[] {((Number)o).longValue(), 1};
		}
		throw new IllegalArgumentException("Cannot convert " + describe(o) + " to a rational");
	}

	/**
	 * Approximates a double by a fraction with 32-bit terms. Integral values become {@code n/1};
	 * everything else goes through a continued fraction expansion that stops once the fraction
	 * reproduces the value to within 1e-9 relative error or the terms would overflow.
	 *
	 * @param value a finite number; for an unsigned rational it must not be negative
	 * @param signed whether the target type is SRATIONAL
	 * @return {numerator, denominator}
	 */
	public static long[] toRational(double value, boolean signed) {
		if(Double.isNaN(value) || Double.isInfinite(value))
			throw new IllegalArgumentException("Cannot store " + value + " as a rational");
		if(!signed && value < 0)
			throw new IllegalArgumentException("Negative value " + value + " needs an SRATIONAL field");
		long limit = signed ? Integer.MAX_VALUE : 0xffffffffL;
		double abs = Math.abs(value);
		long sign = value < 0 ? -1 : 1;
		if(abs == Math.rint(abs) && abs <= limit)
			return new long[] {sign * (long)abs, 1};
		// Continued fraction: h(n) = a(n) h(n-1) + h(n-2), k(n) likewise
		long h0 = 1, h1 = 0, k0 = 0, k1 = 1; // h(-1)=1,h(-2)=0,k(-1)=0,k(-2)=1
		double x = abs;
		long bestNum = Math.round(abs), bestDen = 1;
		for(int i = 0; i < 64; i++) {
			double a = Math.floor(x);
			if(a > limit) break;
			long h = (long)a * h0 + h1;
			long k = (long)a * k0 + k1;
			if(h > limit || k > limit || h < 0 || k < 0) break;
			bestNum = h; bestDen = k;
			if(Math.abs((double)h / k - abs) <= abs * 1e-9) break;
			double frac = x - a;
			if(frac < 1e-15) break;
			x = 1.0 / frac;
			h1 = h0; h0 = h; k1 = k0; k0 = k;
		}
		return new long[] {sign * bestNum, bestDen};
	}

	private static String describe(Object value) {
		if(value == null) return "null";
		String type = value.getClass().getSimpleName();
		if(value.getClass().isArray()) return type + " of length " + Array.getLength(value);
		return type + " " + value;
	}
	
	public String getName() {
		return name;
	}
	
	public short getValue() {
		return value;
	}
	
	@Override
    public String toString() {
		return name;
	}
	
    public static FieldType fromShort(short value) {
       	FieldType fieldType = typeMap.get(value);
    	if (fieldType == null)
    	   return UNKNOWN;
   		return fieldType;
    }
    
    private static final Map<Short, FieldType> typeMap = new HashMap<Short, FieldType>();
       
    static
    {
      for(FieldType fieldType : values())
           typeMap.put(fieldType.getValue(), fieldType);
    }
    
    public static boolean validateData(FieldType type, Object data) {
    	if(data == null) throw new IllegalArgumentException("Input data is null");
    	boolean retValue = false;
    	Class<?> typeClass = data.getClass();
    	switch(type) {
    		case ASCII:
    		case WINDOWSXP: // Not a real TIFF field type, just a convenient way to add Windows XP field as a sting
				if(typeClass == String.class) {
					retValue = true;    				
				}
				break;
    		case BYTE:
    		case SBYTE:
    		case UNDEFINED:
    			if(typeClass == byte[].class) {
    				byte[] byteData = (byte[])data;
    				if(byteData.length > 0) retValue = true;
    			}
    			break;
    		case SHORT:
    		case SSHORT:
    			if(typeClass == short[].class) {
    				short[] shortData = (short[])data;
    				if(shortData.length > 0) retValue = true;    				
    			}
    			break;
    		case LONG:
    		case SLONG:
    			if(typeClass == int[].class) {
    				int[] intData = (int[])data;
    				if(intData.length > 0) retValue = true;    				
    			}
    			break;
    		case RATIONAL:
    		case SRATIONAL:
    			if(typeClass == int[].class) {
    				int[] intData = (int[])data;
    				if(intData.length > 0 && intData.length % 2 == 0) retValue = true;  				
    			}
    			break;
    		default:
    	}
    	
		return retValue;    	
    }
	
	private final String name;
	private final short value;
}
