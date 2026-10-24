package cn.xianhua.pocketkit.core;

/**
 * 面向用户的工具错误。
 * message 会原样展示给用户，必须说明「发生了什么」以及「怎么改」，不道歉、不含糊。
 */
public class ToolException extends Exception {
    public ToolException(String message) {
        super(message);
    }

    public ToolException(String message, Throwable cause) {
        super(message, cause);
    }
}
