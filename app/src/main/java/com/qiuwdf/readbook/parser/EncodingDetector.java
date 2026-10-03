package com.qiuwdf.readbook.parser;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;

/**
 * 简单可靠的 TXT 编码探测：BOM -> 严格 UTF-8 -> GBK。
 *
 * <p>探测只需要文件头部的一小段样本，且「元数据解析」也只需要头部这一段，
 * 于是对外的 {@link #sample(File, int)} 允许调用方读一次样本、
 * 同时用于 {@link #detect(byte[])} 与元数据解析，避免一本书读盘两遍。
 */
public final class EncodingDetector {

    /**
     * 默认采样字节数。头部元数据（书名/作者/…/简介）都在这一段里，
     * 32KB 足够覆盖（实际书头只有 1~2KB），同时把每本书的读盘量压到最小。
     */
    public static final int SAMPLE_BYTES = 32 * 1024;

    private EncodingDetector() {
    }

    public static String detect(File f) {
        byte[] data = readHead(f, SAMPLE_BYTES);
        return detect(data);
    }

    /** 从已读好的头部样本判定编码（与 {@link #detect(File)} 同一套规则） */
    public static String detect(byte[] data) {
        if (data == null || data.length == 0) {
            return "UTF-8";
        }

        // BOM
        if (data.length >= 3 && (data[0] & 0xFF) == 0xEF && (data[1] & 0xFF) == 0xBB && (data[2] & 0xFF) == 0xBF) {
            return "UTF-8";
        }
        if (data.length >= 2 && (data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xFE) {
            return "UTF-16LE";
        }
        if (data.length >= 2 && (data[0] & 0xFF) == 0xFE && (data[1] & 0xFF) == 0xFF) {
            return "UTF-16BE";
        }

        // 无 BOM 的 UTF-16：0x00 比例很高
        int zeros = 0;
        for (byte b : data) {
            if (b == 0) {
                zeros++;
            }
        }
        if (zeros > data.length / 4) {
            return (zeros % 2 == 0 && (data[0] & 0xFF) != 0) ? "UTF-16LE" : "UTF-16BE";
        }

        if (looksLikeUtf8(data)) {
            return "UTF-8";
        }
        return "GBK";
    }

    private static boolean looksLikeUtf8(byte[] data) {
        CharsetDecoder dec = Charset.forName("UTF-8").newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        // 结尾可能截断了多字节字符，最多回退 3 个字节重试
        for (int cut = 0; cut < 4; cut++) {
            int len = data.length - cut;
            if (len <= 0) {
                break;
            }
            try {
                dec.decode(ByteBuffer.wrap(data, 0, len));
                return true;
            } catch (CharacterCodingException e) {
                dec.reset();
            }
        }
        return false;
    }

    /** 读取文件头部样本（最多 max 字节）；文件更短或读取失败时返回实际读到的内容 */
    public static byte[] sample(File f, int max) {
        return readHead(f, max);
    }

    private static byte[] readHead(File f, int max) {
        if (f == null || max <= 0) {
            return new byte[0];
        }
        BufferedInputStream in = null;
        try {
            // 先看文件有多长，避免「先开 32KB 再复制一份」的双倍分配（扫几千本时这笔垃圾很可观）
            long len = f.length();
            int cap = (int) Math.min(max, len > 0 ? len : 0);
            if (cap == 0) {
                return new byte[0];
            }
            in = new BufferedInputStream(new FileInputStream(f), 8192);
            byte[] buf = new byte[cap];
            int off = 0;
            int n;
            while (off < cap && (n = in.read(buf, off, cap - off)) > 0) {
                off += n;
            }
            if (off == cap) {
                return buf;
            }
            byte[] out = new byte[off];
            System.arraycopy(buf, 0, out, 0, off);
            return out;
        } catch (Exception e) {
            return new byte[0];
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Exception ignore) {
                }
            }
        }
    }
}
