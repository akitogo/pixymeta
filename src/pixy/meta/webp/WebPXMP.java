/**
 * Copyright (C) 2026 by akitogo.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */

package pixy.meta.webp;

import java.io.IOException;
import java.io.OutputStream;

import pixy.meta.xmp.XMP;

/**
 * XMP read from the "XMP " chunk of a WebP image.
 */
public class WebPXMP extends XMP {

	public WebPXMP(byte[] data) {
		super(data);
	}

	public WebPXMP(String xmp) {
		super(xmp);
	}

	/**
	 * Writes the XMP packet as stored in the WebP chunk payload.
	 */
	@Override
	public void write(OutputStream os) throws IOException {
		byte[] data = getData();
		if(data != null)
			os.write(data);
	}
}
