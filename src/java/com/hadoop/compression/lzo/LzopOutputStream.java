/*
 * This file is part of Hadoop-Gpl-Compression.
 *
 * Hadoop-Gpl-Compression is free software: you can redistribute it
 * and/or modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 *
 * Hadoop-Gpl-Compression is distributed in the hope that it will be
 * useful, but WITHOUT ANY WARRANTY; without even the implied warranty
 * of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Hadoop-Gpl-Compression.  If not, see
 * <http://www.gnu.org/licenses/>.
 */

package com.hadoop.compression.lzo;

import java.io.IOException;
import java.io.OutputStream;
import java.util.zip.Adler32;

import org.apache.hadoop.io.DataOutputBuffer;
import org.apache.hadoop.io.compress.BlockCompressorStream;
import org.apache.hadoop.io.compress.Compressor;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

public class LzopOutputStream extends BlockCompressorStream {
  private static final Log LOG = LogFactory.getLog(LzopOutputStream.class);

  /**
   * Write an lzop-compatible header to the OutputStream provided.
   */
  protected static void writeLzopHeader(OutputStream out,
          LzoCompressor.CompressionStrategy strategy) throws IOException {
    DataOutputBuffer dob = new DataOutputBuffer();
    try {
      dob.writeShort(LzopCodec.LZOP_VERSION);
      dob.writeShort(LzoCompressor.LZO_LIBRARY_VERSION);
      dob.writeShort(LzopCodec.LZOP_COMPAT_VERSION);
      switch (strategy) {
      case LZO1X_1:
        dob.writeByte(1);
        dob.writeByte(5);
        break;
      case LZO1X_15:
        dob.writeByte(2);
        dob.writeByte(1);
        break;
      case LZO1X_999:
        dob.writeByte(3);
        dob.writeByte(9);
        break;
      default:
        throw new IOException("Incompatible lzop strategy: " + strategy);
      }
      dob.writeInt(0);                                    // all flags 0
      dob.writeInt(0x81A4);                               // mode
      dob.writeInt((int)(System.currentTimeMillis() / 1000)); // mtime
      dob.writeInt(0);                                    // gmtdiff ignored
      dob.writeByte(0);                                   // no filename
      Adler32 headerChecksum = new Adler32();
      headerChecksum.update(dob.getData(), 0, dob.getLength());
      int hc = (int)headerChecksum.getValue();
      dob.writeInt(hc);
      out.write(LzopCodec.LZO_MAGIC);
      out.write(dob.getData(), 0, dob.getLength());
    } finally {
      dob.close();
    }
  }

  public LzopOutputStream(OutputStream out, Compressor compressor,
          int bufferSize, LzoCompressor.CompressionStrategy strategy)
  throws IOException {
    super(out, compressor, bufferSize, strategy.name().contains("LZO1")
            ? (bufferSize >> 4) + 64 + 3 : (bufferSize >> 3) + 128 + 3);
    writeLzopHeader(out, strategy);
  }

  /**
   * Close the underlying stream and write a null word to the output stream.
   */
  @Override
  public void close() throws IOException {
    if (!closed) {
      finish();
      out.write(new byte[]{ 0, 0, 0, 0 });
      out.close();
      closed = true;
    }
  }

  @Override
  protected void compress() throws IOException {
    int len = compressor.compress(buffer, 0, buffer.length);
    if (len > 0) {
      // If the compressed buffer is actually larger than the uncompressed buffer,
      // the LZO specification says that we should write the uncompressed bytes rather
      // than the compressed bytes.  The decompressor understands this because both sizes
      // get written to the stream.
      if (compressor.getBytesRead() < compressor.getBytesWritten()) {
        // Compression actually increased the size of the buffer, so write the uncompressed bytes.
        byte[] uncompressed = ((LzoCompressor)compressor).uncompressedBytes();
        rawWriteInt(uncompressed.length);
        out.write(uncompressed, 0, uncompressed.length);
      } else {
        // Write out the compressed chunk.
        rawWriteInt(len);
        out.write(buffer, 0, len);
      }
    }
  }

  private void rawWriteInt(int v) throws IOException {
    out.write((v >>> 24) & 0xFF);
    out.write((v >>> 16) & 0xFF);
    out.write((v >>>  8) & 0xFF);
    out.write((v >>>  0) & 0xFF);
  }
}
