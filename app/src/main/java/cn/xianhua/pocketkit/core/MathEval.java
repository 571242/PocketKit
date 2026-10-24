package cn.xianhua.pocketkit.core;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * 四则运算表达式求值。手写递归下降解析器。
 *
 * 刻意不用任何脚本引擎（Android 上没有 evalJavaScript，也不该为了算 1+1 引入 JS 引擎）。
 * 用 BigDecimal 计算，因此 0.1+0.2 得到 0.3 而不是 0.30000000000000004。
 *
 * 支持：+ - * / % ^ 、括号、一元正负号、函数 sqrt/abs/round/floor/ceil、
 * 常量 pi/e。不支持变量与赋值，因为计算器不需要。
 */
public final class MathEval {

    /** 结果保留的有效位数。除法除不尽时用它收敛，并标记近似。 */
    private static final MathContext MC = new MathContext(20, RoundingMode.HALF_UP);
    private static final int DISPLAY_SIGNIFICANT = 12;

    public static final class Result {
        public final BigDecimal value;
        /** 除法除不尽、或结果被截断到显示位数。 */
        public final boolean approximate;
        /** 展示用文本，已去掉浮点噪声。 */
        public final String text;

        Result(BigDecimal value, boolean approximate) {
            this.value = value;
            this.approximate = approximate;
            this.text = UnitTool.format(trim(value));
        }

        @Override
        public String toString() {
            return text;
        }
    }

    private String src;
    private int pos;
    /**
     * Nesting guard: parseAtom recurses through parseExpression once per '(' and the JVM stack is
     * only a few thousand frames deep, so a long run of '(' raised StackOverflowError, which is an
     * Error and therefore escaped the ToolException handling all the way to the main thread.
     */
    private int depth;
    private static final int MAX_DEPTH = 64;

    private MathEval(String src) {
        this.src = src;
        this.pos = 0;
    }

    public static Result eval(String expression) throws ToolException {
        if (expression == null || expression.trim().isEmpty()) {
            throw new ToolException("没有可计算的表达式。请输入例如 (1+2)*3。");
        }
        MathEval p = new MathEval(expression);
        BigDecimal v = p.parseExpression();
        p.skipSpaces();
        if (p.pos < p.src.length()) {
            char c = p.src.charAt(p.pos);
            String hint = (c == '=') ? "本工具只求值，不做赋值。去掉「=」再试。"
                    : "第 " + (p.pos + 1) + " 个字符「" + c + "」是多余的，请检查括号或运算符。";
            throw new ToolException("表达式无法解析：" + hint);
        }
        return new Result(v, p.approximate);
    }

    private boolean approximate;

    // expr := term (('+'|'-') term)*
    private BigDecimal parseExpression() throws ToolException {
        BigDecimal left = parseTerm();
        while (true) {
            skipSpaces();
            if (peek('+')) {
                pos++;
                left = left.add(parseTerm(), MC);
            } else if (peek('-')) {
                pos++;
                left = left.subtract(parseTerm(), MC);
            } else {
                return left;
            }
        }
    }

    // term := unary (('*'|'/'|'%') unary)*
    private BigDecimal parseTerm() throws ToolException {
        BigDecimal left = parseUnary();
        while (true) {
            skipSpaces();
            if (peek('*')) {
                pos++;
                // 支持 ** 作为幂（^ 的常见写法）
                if (peek('*')) {
                    pos++;
                    left = power(left, parseUnary());
                } else {
                    left = left.multiply(parseUnary(), MC);
                }
            } else if (peek('/')) {
                pos++;
                BigDecimal right = parseUnary();
                if (right.signum() == 0) {
                    throw new ToolException("除数为 0，无法计算。请检查「/」后面的表达式。");
                }
                BigDecimal q = left.divide(right, MC);
                if (q.multiply(right, MC).compareTo(left) != 0) {
                    approximate = true;
                }
                left = q;
            } else if (peek('%')) {
                pos++;
                BigDecimal right = parseUnary();
                if (right.signum() == 0) {
                    throw new ToolException("取余的除数为 0，无法计算。");
                }
                left = left.remainder(right, MC);
            } else {
                return left;
            }
        }
    }

    // unary := ('+'|'-') unary | power
    private BigDecimal parseUnary() throws ToolException {
        skipSpaces();
        if (peek('-')) {
            pos++;
            return parseUnary().negate();
        }
        if (peek('+')) {
            pos++;
            return parseUnary();
        }
        return parsePower();
    }

    // power := atom ('^' unary)?
    private BigDecimal parsePower() throws ToolException {
        BigDecimal base = parseAtom();
        skipSpaces();
        if (peek('^')) {
            pos++;
            return power(base, parseUnary());
        }
        return base;
    }

    private BigDecimal power(BigDecimal base, BigDecimal exp) throws ToolException {
        if (exp.scale() <= 0 && exp.abs().compareTo(BigDecimal.valueOf(999)) <= 0) {
            int e = exp.intValueExact();
            if (e == 0) {
                return BigDecimal.ONE;
            }
            if (e < 0) {
                if (base.signum() == 0) {
                    throw new ToolException("0 不能取负数次幂。");
                }
                return BigDecimal.ONE.divide(base.pow(-e, MC), MC);
            }
            return base.pow(e, MC);
        }
        double b = base.doubleValue();
        double e = exp.doubleValue();
        double r = Math.pow(b, e);
        if (Double.isNaN(r) || Double.isInfinite(r)) {
            throw new ToolException("幂运算结果不是一个有限数。负数不能开偶次方。");
        }
        approximate = true;
        return new BigDecimal(r, MC);
    }

    // atom := number | '(' expr ')' | func '(' expr ')' | constant
    private BigDecimal parseAtom() throws ToolException {
        skipSpaces();
        if (pos >= src.length()) {
            throw new ToolException("表达式在末尾就结束了，可能少了一个数字或右括号。");
        }
        char c = src.charAt(pos);
        if (c == '(') {
            if (++depth > MAX_DEPTH) {
                throw new ToolException("括号嵌套超过 " + MAX_DEPTH + " 层，请拆成几步计算。");
            }
            pos++;
            BigDecimal v;
            try {
                v = parseExpression();
            } finally {
                depth--;
            }
            skipSpaces();
            if (!peek(')')) {
                throw new ToolException("括号没有配对：缺少一个右括号「)」。");
            }
            pos++;
            return v;
        }
        if (c == ')') {
            throw new ToolException("多了一个右括号「)」，或者括号里是空的。");
        }
        if (Character.isDigit(c) || c == '.') {
            return parseNumber();
        }
        if (Character.isLetter(c)) {
            String name = readName();
            String lower = name.toLowerCase();
            if (lower.equals("pi")) {
                return new BigDecimal(Math.PI, MC);
            }
            if (lower.equals("e")) {
                return new BigDecimal(Math.E, MC);
            }
            skipSpaces();
            if (!peek('(')) {
                if (peek('=')) {
                    throw new ToolException("「" + name + "=」看起来像赋值。"
                            + "本工具只求值，不做赋值，也没有变量。去掉「" + name + "=」再试。");
                }
                throw new ToolException("「" + name + "」不是可用的常量。"
                        + "内置常量只有 pi 和 e；函数后面要跟括号，例如 sqrt(2)。");
            }
            pos++;
            // Function arguments recurse exactly like a parenthesis, so they share the same guard:
            // sqrt(sqrt(sqrt(… would otherwise overrun the stack just as a run of '(' does.
            if (++depth > MAX_DEPTH) {
                throw new ToolException("括号嵌套超过 " + MAX_DEPTH + " 层，请拆成几步计算。");
            }
            BigDecimal arg;
            try {
                arg = parseExpression();
            } finally {
                depth--;
            }
            skipSpaces();
            if (!peek(')')) {
                throw new ToolException("函数 " + name + " 缺少右括号「)」。");
            }
            pos++;
            return applyFunction(lower, arg);
        }
        throw new ToolException("第 " + (pos + 1) + " 个字符「" + c + "」不能作为数字的开头。");
    }

    private BigDecimal applyFunction(String name, BigDecimal arg) throws ToolException {
        switch (name) {
            case "sqrt": {
                if (arg.signum() < 0) {
                    throw new ToolException("sqrt 的参数是负数（" + UnitTool.format(arg) + "），"
                            + "实数范围内没有平方根。");
                }
                double r = Math.sqrt(arg.doubleValue());
                approximate = !isExactDouble(arg) || r != Math.floor(r);
                return new BigDecimal(r, MC);
            }
            case "abs":
                return arg.abs();
            case "round":
                return arg.setScale(0, RoundingMode.HALF_UP);
            case "floor":
                return arg.setScale(0, RoundingMode.FLOOR);
            case "ceil":
                return arg.setScale(0, RoundingMode.CEILING);
            default:
                throw new ToolException("没有名为「" + name + "」的函数。"
                        + "可用的是 sqrt、abs、round、floor、ceil。");
        }
    }

    private static boolean isExactDouble(BigDecimal v) {
        try {
            return v.doubleValue() == Math.rint(v.doubleValue());
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private BigDecimal parseNumber() throws ToolException {
        int start = pos;
        boolean dot = false;
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (Character.isDigit(c)) {
                pos++;
            } else if (c == '.' && !dot) {
                dot = true;
                pos++;
            } else {
                break;
            }
        }
        String s = src.substring(start, pos);
        if (s.equals(".")) {
            throw new ToolException("第 " + (start + 1) + " 个字符只有一个「.」，它前面或后面缺少数字。");
        }
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            throw new ToolException("「" + s + "」不是有效数字。");
        }
    }

    private String readName() {
        int start = pos;
        while (pos < src.length() && Character.isLetter(src.charAt(pos))) {
            pos++;
        }
        return src.substring(start, pos);
    }

    private void skipSpaces() {
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\u3000') {
                pos++;
            } else {
                break;
            }
        }
    }

    private boolean peek(char c) {
        return pos < src.length() && src.charAt(pos) == c;
    }

    /** 收敛到 12 位有效数字再去掉尾零，避免展示 0.30000000000000004 这类噪声。 */
    static BigDecimal trim(BigDecimal v) {
        if (v.signum() == 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal r = v.round(new MathContext(DISPLAY_SIGNIFICANT, RoundingMode.HALF_UP));
        return r.stripTrailingZeros();
    }
}
