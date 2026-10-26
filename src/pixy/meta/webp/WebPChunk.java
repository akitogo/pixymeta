/**
 * Copyright (C) 2026 by akitogo.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */

package pixy.meta.webp;

import java.nio.charset.StandardCharsets;

/**
 * One chunk of a WebP RIFF container: a four character code and its payload (without the
 * padding byte RIFF adds after odd sized chunks).
 */
public class WebPChunk {
	// Chunk FourCCs defined by the WebP container specification
	public static final String VP8 = "VP8 ";
	public static final String VP8L = "VP8L";
	public static final String VP8X = "VP8X";
	public static final String ALPH = "ALPH";
	public static final String ANIM = "ANIM";
	public static final String ANMF = "ANMF";
	public static final String ICCP = "ICCP";
	public static final String EXIF = "EXIF";
	public static final String XMP = "XMP ";

	private final String fourCC;
	private final byte[] data;

	public WebPChunk(String fourCC, byte[] data) {
		if(fourCC == null || fourCC.getBytes(StandardCharsets.ISO_8859_1).length != 4)
			throw new IllegalArgumentException("A chunk FourCC must be four characters: " + fourCC);
		if(data == null)
			throw new IllegalArgumentException("Chunk data is null");
		this.fourCC = fourCC;
		this.data = data;
	}

	public String getFourCC() {
		return fourCC;
	}

	public byte[] getData() {
		return data.clone();
	}

	public int getLength() {
		return data.length;
	}

	// Internal access without the defensive copy
	byte[] data() {
		return data;
	}

	@Override
	public String toString() {
		return "'" + fourCC + "' (" + data.length + " bytes)";
	}
}
