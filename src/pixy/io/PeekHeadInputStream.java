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
 * PeekHeadInputStream.java
 *
 * Who   Date       Description
 * ====  =========  ==============================================================
 * AK    26Oct2026  Added a variable length peek so a short stream can still be sniffed
 * WY    26Sep2015  Initial creation
 */

package pixy.io;

import java.io.*;

import pixy.util.ArrayUtils;

/**
 * Lightweight stream wrapper which allows to peek a
 * fixed length of bytes from the current stream head
 *
 */
public class PeekHeadInputStream extends InputStream {
	/** The source stream. */
    private InputStream src;

	/**
	 * Buffer to hold the peeked bytes.
	 */
	private byte[] buffer;

	/**
	 * Current buffer position.
	 */
	private int position;
	
	private boolean closed;

	/**
	 * @param bytesToPeek number of bytes to peek
	 * @param src The source InputStream to use
	 */
	public PeekHeadInputStream(InputStream src, int bytesToPeek) throws IOException {
		this.src = src;
		this.buffer = new byte[bytesToPeek];
		// A stream shorter than bytesToPeek (e.g. a file under 4 bytes) throws EOFException here.
		// Propagate it as the IOException it is, rather than wrapping it in an unchecked
		// RuntimeException, so callers that already handle IOException catch this case too.
		IOUtils.readFully(src, buffer);
	}
	
	/**
	 * Peeks up to maxBytesToPeek bytes, but accepts a shorter stream as long as it has at least
	 * minBytesToPeek bytes. Use {@link #getPeekLength()} to find out how many were peeked.
	 * 
	 * @param src The source InputStream to use
	 * @param minBytesToPeek minimum number of bytes the stream must have
	 * @param maxBytesToPeek number of bytes to peek if available
	 */
	public PeekHeadInputStream(InputStream src, int minBytesToPeek, int maxBytesToPeek) throws IOException {
		if(minBytesToPeek > maxBytesToPeek)
			throw new IllegalArgumentException("Minimum peek length larger than maximum");
		this.src = src;
		byte[] head = new byte[maxBytesToPeek];
		int n = 0;
		while(n < maxBytesToPeek) {
			int count = src.read(head, n, maxBytesToPeek - n);
			if(count < 0) break;
			n += count;
		}
		// Same contract as the fixed length constructor: too short a stream is an IOException
		if(n < minBytesToPeek) throw new EOFException();
		this.buffer = (n == maxBytesToPeek) ? head : ArrayUtils.subArray(head, 0, n);
	}
	
	/**
	 * @return the number of bytes available to {@link #peek(int)}
	 */
	public int getPeekLength() throws IOException {
		ensureOpen();
		return buffer.length;
	}
	
	public void close() throws IOException {
		if(closed) return;
		buffer = null;
		src.close();
		src = null;
		closed = true;
	}
	
	public void shallowClose() throws IOException {
		if(closed) return;
		buffer = null;
		closed = true;
	}
	
	/**
	 * Check to make sure that this stream has not been closed
	 */
    private  void ensureOpen() throws IOException {
    	if (closed)
    		throw new IOException("Stream closed");
    }
	
	public byte[] peek(int len) throws IOException {
		ensureOpen();
		if(len <= buffer.length) return ArrayUtils.subArray(buffer, 0, len);
		throw new IllegalArgumentException("Peek length larger than buffer");
	}

	@Override
	public int read() throws IOException {
		ensureOpen();
		if (position >= buffer.length)
			return src.read();
		else
			return (buffer[position++]&0xff);
	}
	
	@Override
	public int read(byte[] b, int off, int len) throws IOException {
		ensureOpen();
		if (position >= buffer.length) {
			return src.read(b, off, len);
		} else if(position + len > buffer.length) {
			int bytesAvailable = buffer.length - position;
			System.arraycopy(buffer, position, b, off, bytesAvailable);
			position += bytesAvailable;
			// src returns -1 at end of stream; don't let that eat one of the buffered bytes
			int count = src.read(b, off + bytesAvailable, len - bytesAvailable);
			return bytesAvailable + Math.max(count, 0);
		}
		else {
			System.arraycopy(buffer, position, b, off, len);
			position += len;
			return len;
		}
	}
}
