package com.deltawaken.adbtile.adb;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Un message du protocole adb : en-tête de 24 octets, petit-boutiste, suivi de sa charge utile. */
final class AdbMessage {

    static final int CNXN = 0x4e584e43;
    static final int AUTH = 0x48545541;
    static final int OPEN = 0x4e45504f;
    static final int OKAY = 0x59414b4f;
    static final int CLSE = 0x45534c43;
    static final int WRTE = 0x45545257;
    static final int STLS = 0x534c5453;

    private static final int HEADER_SIZE = 24;
    private static final int MAX_PAYLOAD = 1024 * 1024;

    final int command;
    final int arg0;
    final int arg1;
    final byte[] payload;

    private AdbMessage(int command, int arg0, int arg1, byte[] payload) {
        this.command = command;
        this.arg0 = arg0;
        this.arg1 = arg1;
        this.payload = payload;
    }

    static void write(OutputStream out, int command, int arg0, int arg1, byte[] payload)
            throws IOException {
        int length = payload == null ? 0 : payload.length;
        int checksum = 0;
        for (int i = 0; i < length; i++) {
            checksum += payload[i] & 0xff;
        }
        ByteBuffer buffer = ByteBuffer.allocate(HEADER_SIZE + length).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(command).putInt(arg0).putInt(arg1)
                .putInt(length).putInt(checksum).putInt(~command);
        if (length > 0) {
            buffer.put(payload);
        }
        out.write(buffer.array());
        out.flush();
    }

    static AdbMessage read(InputStream in) throws IOException {
        ByteBuffer header = ByteBuffer.wrap(readFully(in, HEADER_SIZE)).order(ByteOrder.LITTLE_ENDIAN);
        int command = header.getInt();
        int arg0 = header.getInt();
        int arg1 = header.getInt();
        int length = header.getInt();
        header.getInt(); // somme de contrôle, ignorée depuis la version 0x01000001
        int magic = header.getInt();
        if (magic != ~command || length < 0 || length > MAX_PAYLOAD) {
            throw new IOException("En-tête adb invalide");
        }
        return new AdbMessage(command, arg0, arg1, readFully(in, length));
    }

    private static byte[] readFully(InputStream in, int length) throws IOException {
        byte[] data = new byte[length];
        int offset = 0;
        while (offset < length) {
            int read = in.read(data, offset, length - offset);
            if (read < 0) {
                throw new EOFException();
            }
            offset += read;
        }
        return data;
    }
}
