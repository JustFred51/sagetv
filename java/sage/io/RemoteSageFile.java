/*
 * Copyright 2015 The SageTV Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package sage.io;

import sage.Sage;
import sage.SageTVConnection;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public class RemoteSageFile implements SageFileSource
{
  private final String hostname;
  private final String remoteFilename;
  private final String transcodeMode;
  private final boolean readonly;
  private final int uploadId;

  private final boolean forceActive;
  private boolean activeFile = false;
  private long currTotalSize = 0;
  private long maxRemoteSize = 0;

  private long remoteOffset = 0;
  private Socket socket = null;
  private DataOutputStream outStream = null;
  private DataInputStream inStream = null;

  /**
   * Open a file remotely via the media server for read/write.
   *
   * @param hostname The hostname of the media server hosting this file.
   * @param file The full path and file to be opened.
   * @param uploadId The ID that authorizes write access.
   * @throws IOException If there is an I/O related error.
   */
  public RemoteSageFile(String hostname, File file, int uploadId) throws IOException
  {
    this(hostname, file.getPath(), uploadId);
  }

  /**
   * Open a file remotely via the media server for read/write.
   *
   * @param hostname The hostname of the media server hosting this file.
   * @param name The full path and file to be opened.
   * @param uploadId The ID that authorizes write access.
   * @throws IOException If there is an I/O related error.
   */
  public RemoteSageFile(String hostname, String name, int uploadId) throws IOException
  {
    this.hostname = hostname;
    remoteFilename = name;
    this.transcodeMode = null;
    this.readonly = false;
    this.forceActive = false;
    this.uploadId = uploadId;
    connect();
  }

  /**
   * Open a file remotely via the media server for read only.
   *
   * @param hostname The hostname of the media server hosting this file.
   * @param file The file to be opened.
   * @throws IOException If there is an I/O related error.
   */
  public RemoteSageFile(String hostname, File file) throws IOException
  {
    this(hostname, file, null);
  }

  /**
   * Open a file remotely via the media server for read only.
   *
   * @param hostname The hostname of the media server hosting this file.
   * @param name The full path and file to be opened.
   * @throws IOException If there is an I/O related error.
   */
  public RemoteSageFile(String hostname, String name) throws IOException
  {
    this(hostname, name, null);
  }

  /**
   * Open a file remotely via the media server to be transcoded.
   *
   * @param hostname The hostname of the media server hosting this file.
   * @param file The file to be opened.
   * @param transcodeMode Set the transcode mode.
   * @throws IOException If there is an I/O related error.
   */
  public RemoteSageFile(String hostname, File file, String transcodeMode) throws IOException
  {
    this(hostname, file.getPath(), transcodeMode);
  }

  /**
   * Open a file remotely via the media server to be transcoded.
   *
   * @param hostname The hostname of the media server hosting this file.
   * @param name The full path and file to be opened.
   * @param transcodeMode Set the transcode mode.
   * @throws IOException If there is an I/O related error.
   */
  public RemoteSageFile(String hostname, String name, String transcodeMode) throws IOException
  {
    this.hostname = hostname;
    remoteFilename = name;
    this.transcodeMode = transcodeMode;
    this.readonly = true;
    this.forceActive = false;
    this.uploadId = -1;
    connect();
  }

  /**
   * Open a file remotely via the media server for read only.
   *
   * @param hostname The hostname of the media server hosting this file.
   * @param file The file to be opened.
   * @param forceActive Always assume the file is active. Use this with care.
   * @throws IOException If there is an I/O related error.
   */
  public RemoteSageFile(String hostname, File file, boolean forceActive) throws IOException
  {
    this(hostname, file.getPath(), forceActive);
  }

  /**
   * Open a file remotely via the media server for read only.
   *
   * @param hostname The hostname of the media server hosting this file.
   * @param name The full path and file to be opened.
   * @param forceActive Always assume the file is active. Use this with care.
   * @throws IOException If there is an I/O related error.
   */
  public RemoteSageFile(String hostname, String name, boolean forceActive) throws IOException
  {
    this.hostname = hostname;
    remoteFilename = name;
    this.transcodeMode = null;
    this.readonly = true;
    this.forceActive = forceActive;
    this.uploadId = -1;
    connect();
  }

  private void connect() throws IOException
  {
    if (socket != null)
      return;

    Socket newSocket = new Socket();
    newSocket.connect(new InetSocketAddress(hostname, 7818));
    newSocket.setSoTimeout(30000);
    newSocket.setTcpNoDelay(true);
    socket = newSocket;

    outStream = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
    inStream = new DataInputStream(new BufferedInputStream(socket.getInputStream()));

    if (transcodeMode != null && transcodeMode.length() > 0)
    {
      outStream.write(("XCODE_SETUP " + transcodeMode + "\r\n").getBytes(Sage.BYTE_CHARSET));
      outStream.flush();
      String response = Sage.readLineBytes(inStream);
      if (!"OK".equals(response))
        throw new java.io.IOException("Error with remote transcode setup for " + transcodeMode + " of: " + response);
    }

    if (readonly)
    {
      outStream.write("OPENW ".getBytes(Sage.BYTE_CHARSET));
      outStream.write(remoteFilename.getBytes(StandardCharsets.UTF_16BE));
    }
    else
    {
      outStream.write("WRITEOPENW ".getBytes(Sage.BYTE_CHARSET));
      outStream.write(remoteFilename.getBytes(StandardCharsets.UTF_16BE));
      outStream.write((" " + Integer.toString(uploadId)).getBytes(Sage.BYTE_CHARSET));
    }

    outStream.write("\r\n".getBytes(Sage.BYTE_CHARSET));
    outStream.flush();

    String response = Sage.readLineBytes(inStream);
    if (!"OK".equals(response))
    {
      // Don't leave the connection open. We can't read anything if we are seeing this error anyway.
      disconnect();
      throw new IOException("Error opening remote file of:" + response);
    }
  }

  private void disconnect()
  {
    if (socket != null)
    {
      try
      {
        outStream.write("QUIT\r\n".getBytes(Sage.BYTE_CHARSET));
        outStream.flush();
      }
      catch (IOException e) {}

      try
      {
        socket.close();
      }
      catch (IOException e) {}
      socket = null;
    }
    if (outStream != null)
    {
      try
      {
        outStream.close();
      }
      catch (IOException e) {}
      outStream = null;
    }
    if (inStream != null)
    {
      try
      {
        inStream.close();
      }
      catch (IOException e) {}
      inStream = null;
    }
  }

  private synchronized void reconnect() throws IOException
  {
    disconnect();
    connect();
  }

  private long getMaxRead(long position, long count) throws IOException
  {
    // We can't read more than what is in the file. The first part checks a cached variable, the
    // second part checks the file directly. Unless the stream really lingers trying to get a
    // massive read towards the end, this should be a very minimal load on the media server.
    count = Math.min(
        maxRemoteSize == 0 || (position + count >= maxRemoteSize && activeFile) ?
            reallyGetLength() - position : maxRemoteSize - position,
        count);

    // If we are trying to read beyond the end of the file, don't read anything.
    return count;
  }

  @Override
  public synchronized int read() throws IOException
  {
    int returnValue;

    if (getMaxRead(remoteOffset, 1) == 0)
    {
      // End of the file.
      return -1;
    }

    try
    {
      outStream.write(("READ " + remoteOffset + " 1\r\n").getBytes(Sage.BYTE_CHARSET));
      outStream.flush();
      returnValue = inStream.read();
    }
    catch (IOException e)
    {
      reconnect();
      outStream.write(("READ " + remoteOffset + " 1\r\n").getBytes(Sage.BYTE_CHARSET));
      outStream.flush();
      returnValue = inStream.read();
    }

    remoteOffset += 1;
    return returnValue & 0xFF;
  }

  @Override
  public int read(byte[] b) throws IOException
  {
    return read(b, 0, b.length);
  }

  @Override
  public synchronized int read(byte[] b, int off, int len) throws IOException
  {
    if (len == 0)
      return len;

    // This cannot return anything greater than len.
    len = (int)getMaxRead(remoteOffset, len);

    // End of the file.
    if (len == 0)
      return -1;

    try
    {
      outStream.write(("READ " + remoteOffset + " " + len + "\r\n").getBytes(Sage.BYTE_CHARSET));
      outStream.flush();
      inStream.readFully(b, off, len);
    }
    catch (IOException e)
    {
      reconnect();
      outStream.write(("READ " + remoteOffset + " " + len + "\r\n").getBytes(Sage.BYTE_CHARSET));
      outStream.flush();
      inStream.readFully(b, off, len);
    }

    remoteOffset += len;
    return len;
  }

  @Override
  public void readFully(byte[] b) throws IOException
  {
    readFully(b, 0, b.length);
  }

  @Override
  public void readFully(byte[] b, int off, int len) throws IOException
  {
    int bytesRead;
    while (len > 0)
    {
      bytesRead = read(b, off, len);

      if (bytesRead == -1)
        throw new EOFException("End of file reached!");

      if (len == bytesRead)
        break;

      len -= bytesRead;
      off += bytesRead;
    }
  }

  @Override
  public synchronized void write(int b) throws IOException
  {
    if (readonly)
      throw new IOException("Remote file is read only.");

    try
    {
      outStream.write(("WRITE " + remoteOffset + " 1\r\n").getBytes(Sage.BYTE_CHARSET));
      outStream.write(b);
      outStream.flush();
    }
    catch (IOException e)
    {
      reconnect();
      outStream.write(("WRITE " + remoteOffset + " 1\r\n").getBytes(Sage.BYTE_CHARSET));
      outStream.write(b);
      outStream.flush();
    }

    remoteOffset += 1;
  }

  @Override
  public void write(byte[] b) throws IOException
  {
    write(b, 0, b.length);
  }

  @Override
  public synchronized void write(byte[] b, int off, int len) throws IOException
  {
    if (readonly)
      throw new IOException("Remote file is read only.");

    if (len == 0)
      return;

    try
    {
      outStream.write(("WRITE " + remoteOffset + " " + len + "\r\n").getBytes(Sage.BYTE_CHARSET));
      outStream.write(b, off, len);
      outStream.flush();
    }
    catch (IOException e)
    {
      reconnect();
      outStream.write(("WRITE " + remoteOffset + " " + len + "\r\n").getBytes(Sage.BYTE_CHARSET));
      outStream.write(b, off, len);
      outStream.flush();
    }

    remoteOffset += len;
  }

  @Override
  public synchronized void randomWrite(long pos, byte[] b, int off, int len) throws IOException
  {
    if (readonly)
      throw new IOException("Remote file is read only.");

    long oldPosition = remoteOffset;
    remoteOffset = pos;
    try
    {
      write(b, off, len);
    }
    finally
    {
      remoteOffset = oldPosition;
    }
  }

  @Override
  public synchronized long skip(long n) throws IOException
  {
    if (n <= 0)
      return 0;

    long pos = remoteOffset;
    long seek = Math.min(pos + n, getMaxRead(remoteOffset, n));

    remoteOffset = seek;

    return seek - pos;
  }

  @Override
  public void seek(long pos)
  {
    remoteOffset = pos;
  }

  @Override
  public long position()
  {
    return remoteOffset;
  }

  private long reallyGetLength() throws IOException
  {
    String response = executeCommand("SIZE\r\n");

    long currAvailSize = Long.parseLong(response.substring(0, response.indexOf(' ')));
    currTotalSize = Long.parseLong(response.substring(response.indexOf(' ') + 1));
    maxRemoteSize = Math.max(maxRemoteSize, currAvailSize);
    if (currAvailSize != currTotalSize || forceActive)
    {
      activeFile = true;
    }
    return maxRemoteSize;
  }

  @Override
  public long length() throws IOException
  {
    if (maxRemoteSize == 0 || activeFile)
    {
      return reallyGetLength();
    }

    return maxRemoteSize;
  }

  @Override
  public long available() throws IOException
  {
    if (remoteOffset < maxRemoteSize)
      return maxRemoteSize - remoteOffset;

    return reallyGetLength() - remoteOffset;
  }

  @Override
  public void setLength(long newLength) throws IOException
  {
    if (readonly)
      throw new IOException("Remote file is read only.");

    String response = executeCommand("TRUNC " + newLength + "\r\n");

    if (!"OK".equals(response))
    {
      // Don't leave the connection open. We can't read anything if we are seeing this error anyway.
      disconnect();
      throw new IOException("Error truncating remote file of:" + response);
    }

    maxRemoteSize = newLength;
  }

  /**
   * Syncs metadata too.
   */
  @Override
  public void sync() throws IOException
  {
    if (readonly)
      return;

    String response = executeCommand("FORCE TRUE" + "\r\n");

    if (!"OK".equals(response))
      throw new IOException("Error forcing remote file of:" + response);
  }

  @Override
  public void flush() throws IOException
  {
    // Nothing to do since we don't buffer anything at this level and there's nothing below it.
  }

  @Override
  public void close() throws IOException
  {
    // This effectively closes the file. Future use of I/O after calling this method will likely
    // result in a null pointer exception.
    disconnect();
  }

  @Override
  public boolean isReadOnly()
  {
    return readonly;
  }

  /**
   * Execute a command on the media server.
   * </p>
   * If \r\n is missing, it will automatically be appended for you. Everything sent with this method
   * will be encoded as Sage.BYTE_CHARSET.
   *
   * @param command The command to execute.
   * @return The response received from the media server.
   * @throws IOException If there was an I/O error.
   */
  public String executeCommand(String command) throws IOException
  {
    command = command.endsWith("\r\n") ? command : (command + "\r\n");

    byte b[] = command.getBytes(Sage.BYTE_CHARSET);
    return executeCommand(b, 0, b.length);
  }

  /**
   * Execute a byte encoded command on the media server.
   *
   * @param command The byte encoded command to execute.
   * @param off The offset in the byte array of the command to be sent.
   * @param len The length of the command to be sent.
   * @return The response received from the media server.
   * @throws IOException If there was an I/O error.
   */
  public synchronized String executeCommand(byte[] command, int off, int len) throws IOException
  {
    try
    {
      outStream.write(command, off, len);
      outStream.flush();
      return Sage.readLineBytes(inStream);
    }
    catch (IOException e)
    {
      reconnect();
      outStream.write(command, off, len);
      outStream.flush();
      return Sage.readLineBytes(inStream);
    }
  }

  @Override
  public SageFileSource getSource()
  {
    return null;
  }
}
