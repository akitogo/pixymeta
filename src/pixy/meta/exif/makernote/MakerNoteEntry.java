/**
 * Copyright (C) 2026 by akitogo.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */

package pixy.meta.exif.makernote;

import java.util.Arrays;

import pixy.image.tiff.FieldType;
import pixy.string.StringUtils;

/**
 * One decoded maker note tag.
 * <p>
 * The value uses the same Java types as the TIFF fields of this library:
 * {@code String} for ASCII, {@code byte[]} for BYTE/SBYTE/UNDEFINED, {@code short[]} for
 * SHORT/SSHORT, {@code int[]} for LONG/SLONG, {@code int[]} numerator/denominator pairs for
 * RATIONAL/SRATIONAL, {@code float[]} for FLOAT and {@code double[]} for DOUBLE. Unsigned types
 * are stored in their signed Java counterparts; use {@link #getValueAsLongs()} for unsigned values.
 */
public class MakerNoteEntry {
	// Maximum number of array elements rendered by getValueAsString()
	private static final int MAX_STRING_REPR_LEN = 16;

	private final int tag;
	private final String name;
	private final FieldType type;
	private final int count;
	private final Object value;

	MakerNoteEntry(int tag, String name, FieldType type, int count, Object value) {
		this.tag = tag;
		this.name = name;
		this.type = type;
		this.count = count;
		this.value = value;
	}

	public int getTag() {
		return tag;
	}

	/**
	 * @return the tag name, or "Unknown tag (0xNNNN)" if the tag is not known
	 */
	public String getName() {
		return name;
	}

	public FieldType getType() {
		return type;
	}

	/**
	 * @return the number of values as declared in the IFD entry
	 */
	public int getCount() {
		return count;
	}

	/**
	 * @return the raw value, see the class description for the Java type per field type
	 */
	public Object getValue() {
		return value;
	}

	/**
	 * Returns integer values widened to long, treating BYTE, SHORT and LONG as unsigned.
	 *
	 * @return the values, or null if this is not an integer typed entry
	 */
	public long[] getValueAsLongs() {
		long[] longs = null;
		switch(type) {
			case BYTE:
			case SBYTE:
				byte[] bytes = (byte[])value;
				longs = new long[bytes.length];
				for(int i = 0; i < bytes.length; i++)
					longs[i] = (type == FieldType.BYTE) ? (bytes[i] & 0xff) : bytes[i];
				break;
			case SHORT:
			case SSHORT:
				short[] shorts = (short[])value;
				longs = new long[shorts.length];
				for(int i = 0; i < shorts.length; i++)
					longs[i] = (type == FieldType.SHORT) ? (shorts[i] & 0xffff) : shorts[i];
				break;
			case LONG:
			case SLONG:
			case IFD:
				int[] ints = (int[])value;
				longs = new long[ints.length];
				for(int i = 0; i < ints.length; i++)
					longs[i] = (type == FieldType.SLONG) ? ints[i] : (ints[i] & 0xffffffffL);
				break;
			default:
		}
		return longs;
	}

	public String getValueAsString() {
		switch(type) {
			case ASCII:
				return ((String)value).trim();
			case UNDEFINED:
				return StringUtils.byteArrayToHexString((byte[])value, 0, MAX_STRING_REPR_LEN);
			case RATIONAL:
			case SRATIONAL:
				int[] rational = (int[])value;
				if(rational.length == 0) return "[]";
				int[] shown = Arrays.copyOf(rational, Math.min(rational.length, 2 * MAX_STRING_REPR_LEN));
				String result = StringUtils.rationalArrayToString(shown, type == FieldType.RATIONAL);
				if(rational.length == 2) return result.substring(1, result.length() - 1);
				return (shown.length < rational.length) ? result.substring(0, result.length() - 1) + " ...]" : result;
			case FLOAT:
				return formatArray(Arrays.toString((float[])value), ((float[])value).length);
			case DOUBLE:
				return formatArray(Arrays.toString((double[])value), ((double[])value).length);
			default:
				long[] longs = getValueAsLongs();
				if(longs == null) return "";
				if(longs.length == 1) return Long.toString(longs[0]);
				StringBuilder sb = new StringBuilder("[");
				for(int i = 0; i < longs.length && i < MAX_STRING_REPR_LEN; i++) {
					if(i > 0) sb.append(",");
					sb.append(longs[i]);
				}
				if(longs.length > MAX_STRING_REPR_LEN) sb.append(" ...");
				return sb.append("]").toString();
		}
	}

	private static String formatArray(String array, int length) {
		// Arrays.toString() renders "[a, b]"; a single value is shown without brackets
		return (length == 1) ? array.substring(1, array.length() - 1) : array;
	}

	@Override
	public String toString() {
		return name + ": " + getValueAsString();
	}
}
