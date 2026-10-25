package com.litemask.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * 服务端共用的 JSON 序列化器。
 *
 * <p>单独一个类而不是各处 new：序列化器有内部缓存，多份实例会重复建缓存；
 * 而且关闭缩进是个有意的决定——响应里的中文原值不少，缩进会让每个响应大一截，
 * 而这些响应只在 localhost 上传输，压缩不划算，可读性也不重要。
 */
public final class Json {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .disable(SerializationFeature.INDENT_OUTPUT);

    private Json() {
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    public static byte[] toBytes(Object value) throws com.fasterxml.jackson.core.JsonProcessingException {
        return MAPPER.writeValueAsBytes(value);
    }

    /**
     * 带缩进的序列化。
     *
     * <p>只给<b>给人看的文件</b>用（映射件）：用户会打开它核对原值，
     * 挤在一行里没法看。接口响应仍然用紧凑的 {@link #toBytes}——
     * 那些内容只在 localhost 上传输，缩进只会让每个响应大一截。
     */
    public static byte[] toPrettyBytes(Object value) throws com.fasterxml.jackson.core.JsonProcessingException {
        return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(value);
    }

    /**
     * 解析请求体。
     *
     * <p>刻意<b>不</b>开 {@code FAIL_ON_UNKNOWN_PROPERTIES}：浏览器端多传一个字段
     * 就让整个请求失败，是很难排查的失败模式，而多出来的字段本来也没有害处。
     *
     * <p>签名是 {@code IOException} 而不是更窄的 {@code JsonProcessingException}：
     * {@code readValue(byte[])} 声明的就是前者，窄化只会逼调用方写无用的 catch。
     */
    public static <T> T read(byte[] body, Class<T> type) throws java.io.IOException {
        return MAPPER.readValue(body, type);
    }
}
