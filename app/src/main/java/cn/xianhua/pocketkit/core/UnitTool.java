package cn.xianhua.pocketkit.core;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 长度 / 重量 / 温度 单位换算。
 * 长度与重量是纯比例换算（基准：米、千克）；温度是仿射变换，单独处理。
 */
public final class UnitTool {

    public static final class Unit {
        public final String id;
        public final String name;
        public final String symbol;
        /** 相对基准单位的倍率（温度用不到）。 */
        public final double factor;

        Unit(String id, String name, String symbol, double factor) {
            this.id = id;
            this.name = name;
            this.symbol = symbol;
            this.factor = factor;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    public static final class Category {
        public final String id;
        public final String name;
        /** 该类的基准单位符号。 */
        public final String baseSymbol;
        public final List<Unit> units;
        /** 温度这类非线性换算为 true，走 toBase / fromBase。 */
        public final boolean affine;

        Category(String id, String name, String baseSymbol, boolean affine, List<Unit> units) {
            this.id = id;
            this.name = name;
            this.baseSymbol = baseSymbol;
            this.affine = affine;
            this.units = Collections.unmodifiableList(units);
        }

        public Unit unit(String unitId) {
            for (Unit u : units) {
                if (u.id.equals(unitId)) {
                    return u;
                }
            }
            return null;
        }
    }

    public static final Category LENGTH;
    public static final Category WEIGHT;
    public static final Category TEMPERATURE;
    public static final List<Category> ALL;

    static {
        List<Unit> length = new ArrayList<>();
        length.add(new Unit("pm", "皮米", "pm", 1e-12));
        length.add(new Unit("nm", "纳米", "nm", 1e-9));
        length.add(new Unit("um", "微米", "μm", 1e-6));
        length.add(new Unit("mm", "毫米", "mm", 0.001));
        length.add(new Unit("cm", "厘米", "cm", 0.01));
        length.add(new Unit("dm", "分米", "dm", 0.1));
        length.add(new Unit("m", "米", "m", 1.0));
        length.add(new Unit("km", "千米", "km", 1000.0));
        length.add(new Unit("in", "英寸", "in", 0.0254));
        length.add(new Unit("ft", "英尺", "ft", 0.3048));
        length.add(new Unit("yd", "码", "yd", 0.9144));
        length.add(new Unit("mi", "英里", "mi", 1609.344));
        length.add(new Unit("nmi", "海里", "nmi", 1852.0));
        length.add(new Unit("fm", "英寻", "fm", 1.8288));
        length.add(new Unit("fur", "弗隆", "fur", 201.168));
        length.add(new Unit("au", "天文单位", "au", 149597870700.0));
        length.add(new Unit("ly", "光年", "ly", 9.4607304725808e15));
        // 市制：1 尺 = 1/3 m，1 丈 = 10 尺，1 寸 = 1/10 尺，1 分 = 1/10 寸，1 毫 = 1/10 分，1 厘 = 1/10 毫
        length.add(new Unit("zhang", "丈（市制）", "丈", 10.0 / 3.0));
        length.add(new Unit("chi", "尺（市制）", "尺", 1.0 / 3.0));
        length.add(new Unit("cun", "寸（市制）", "寸", 1.0 / 30.0));
        length.add(new Unit("fen", "分（市制）", "分", 1.0 / 300.0));
        length.add(new Unit("li2", "厘（市制）", "厘", 1.0 / 3000.0));
        length.add(new Unit("hao", "毫（市制）", "毫", 1.0 / 30000.0));
        length.add(new Unit("li", "里（市制）", "里", 500.0));
        LENGTH = new Category("length", "长度", "m", false, length);

        List<Unit> weight = new ArrayList<>();
        weight.add(new Unit("ug", "微克", "μg", 1e-9));
        weight.add(new Unit("mg", "毫克", "mg", 1e-6));
        weight.add(new Unit("g", "克", "g", 0.001));
        weight.add(new Unit("kg", "千克", "kg", 1.0));
        weight.add(new Unit("t", "吨", "t", 1000.0));
        weight.add(new Unit("q", "公担", "q", 100.0));
        weight.add(new Unit("dan", "担（市制）", "担", 50.0));
        weight.add(new Unit("jin", "斤（市制）", "斤", 0.5));
        weight.add(new Unit("liang", "两（市制）", "两", 0.05));
        weight.add(new Unit("qian", "钱（市制）", "钱", 0.005));
        weight.add(new Unit("fen2", "分（市制）", "分", 0.0005));
        weight.add(new Unit("lb", "磅", "lb", 0.45359237));
        weight.add(new Unit("oz", "盎司", "oz", 0.028349523125));
        weight.add(new Unit("gr", "格令", "gr", 0.00006479891));
        weight.add(new Unit("dr", "打兰", "dr", 0.0017718451953125));
        weight.add(new Unit("ct", "克拉", "ct", 0.0002));
        weight.add(new Unit("st", "英石", "st", 6.35029318));
        weight.add(new Unit("lt", "长吨", "lt", 1016.0469088));
        weight.add(new Unit("sh_tn", "短吨", "tn", 907.18474));
        WEIGHT = new Category("weight", "重量", "kg", false, weight);

        List<Unit> temp = new ArrayList<>();
        temp.add(new Unit("c", "摄氏度", "°C", 1.0));
        temp.add(new Unit("f", "华氏度", "°F", 1.0));
        temp.add(new Unit("k", "开尔文", "K", 1.0));
        temp.add(new Unit("re", "列氏度", "°Re", 1.0));
        temp.add(new Unit("ra", "兰氏度", "°R", 1.0));
        TEMPERATURE = new Category("temperature", "温度", "°C", true, temp);

        List<Category> all = new ArrayList<>();
        all.add(LENGTH);
        all.add(WEIGHT);
        all.add(TEMPERATURE);
        ALL = Collections.unmodifiableList(all);
    }

    /**
     * 全能单位换算（066）的扩展类别：全部是纯比例换算（以基准单位的倍率表示）。
     * 角度以「度」为基准；数据存储以「字节」为基准并提供十进制与二进制两套前缀。
     */
    public static final Category ANGLE;
    public static final Category DATA;
    public static final Category AREA;
    public static final Category VOLUME;
    public static final Category SPEED;
    public static final Category TIME;
    public static final Category PRESSURE;
    public static final Category ENERGY;
    public static final Category POWER;
    public static final Category FORCE;
    public static final Category DENSITY;

    static {
        List<Unit> angle = new ArrayList<>();
        angle.add(new Unit("deg", "度", "°", 1.0));
        angle.add(new Unit("rad", "弧度", "rad", 180.0 / Math.PI));
        angle.add(new Unit("mrad", "毫弧度", "mrad", 0.18 / Math.PI));
        angle.add(new Unit("grad", "百分度", "gon", 0.9));
        angle.add(new Unit("turn", "圈", "r", 360.0));
        angle.add(new Unit("ra", "直角", "∟", 90.0));
        angle.add(new Unit("arcmin", "角分", "′", 1.0 / 60.0));
        angle.add(new Unit("arcsec", "角秒", "″", 1.0 / 3600.0));
        ANGLE = new Category("angle", "角度", "°", false, angle);

        List<Unit> data = new ArrayList<>();
        // 两套前缀必须分开写，因为它们差 4.9%（不是很多工具说的 7%：1 GiB/1 GB = 1.0737，
        // 而 1 GB 换算成 MiB 是 1000/1.048576 ≈ 953.67）。混用是这块最常见的坑。
        data.add(new Unit("bit", "比特", "bit", 0.125));
        data.add(new Unit("B", "字节", "B", 1.0));
        // 二进制前缀（1024 进制，操作系统与内存口径）
        data.add(new Unit("KiB", "千字节 KiB（1024）", "KiB", 1024.0));
        data.add(new Unit("MiB", "兆字节 MiB（1024²）", "MiB", 1024.0 * 1024));
        data.add(new Unit("GiB", "吉字节 GiB（1024³）", "GiB", 1024.0 * 1024 * 1024));
        data.add(new Unit("TiB", "太字节 TiB（1024⁴）", "TiB", Math.pow(1024, 4)));
        data.add(new Unit("PiB", "拍字节 PiB（1024⁵）", "PiB", Math.pow(1024, 5)));
        data.add(new Unit("EiB", "艾字节 EiB（1024⁶）", "EiB", Math.pow(1024, 6)));
        // 十进制前缀（1000 进制，硬盘与网络厂商口径）
        data.add(new Unit("kB", "千字节 kB（1000）", "kB", 1000.0));
        data.add(new Unit("MB", "兆字节 MB（1000²）", "MB", 1e6));
        data.add(new Unit("GB", "吉字节 GB（1000³）", "GB", 1e9));
        data.add(new Unit("TB", "太字节 TB（1000⁴）", "TB", 1e12));
        data.add(new Unit("PB", "拍字节 PB（1000⁵）", "PB", 1e15));
        data.add(new Unit("EB", "艾字节 EB（1000⁶）", "EB", 1e18));
        data.add(new Unit("Mbps", "兆比特每秒", "Mbps", 1e6 / 8.0));
        DATA = new Category("data", "数据存储", "B", false, data);

        List<Unit> area = new ArrayList<>();
        area.add(new Unit("mm2", "平方毫米", "mm²", 1e-6));
        area.add(new Unit("cm2", "平方厘米", "cm²", 1e-4));
        area.add(new Unit("dm2", "平方分米", "dm²", 0.01));
        area.add(new Unit("m2", "平方米", "m²", 1.0));
        area.add(new Unit("km2", "平方千米", "km²", 1e6));
        area.add(new Unit("ha", "公顷", "ha", 10000.0));
        area.add(new Unit("are", "公亩", "a", 100.0));
        // 1 顷 = 100 市亩 = 100 × 2000/3 m².
        area.add(new Unit("qing", "顷", "顷", 200000.0 / 3.0));
        area.add(new Unit("mu", "亩（市制）", "亩", 2000.0 / 3.0));
        area.add(new Unit("fen", "分（市制）", "分", 200.0 / 3.0));
        area.add(new Unit("sq_chi", "平方尺（市制）", "平方尺", 1.0 / 9.0));
        area.add(new Unit("sq_cun", "平方寸（市制）", "平方寸", 1.0 / 900.0));
        area.add(new Unit("in2", "平方英寸", "in²", 0.00064516));
        area.add(new Unit("ft2", "平方英尺", "ft²", 0.09290304));
        area.add(new Unit("yd2", "平方码", "yd²", 0.83612736));
        area.add(new Unit("rd2", "平方杆", "sq rd", 25.29285264));
        area.add(new Unit("ac", "英亩", "ac", 4046.8564224));
        area.add(new Unit("mi2", "平方英里", "mi²", 2589988.110336));
        AREA = new Category("area", "面积", "m²", false, area);

        List<Unit> volume = new ArrayList<>();
        volume.add(new Unit("ul", "微升", "μL", 1e-9));
        volume.add(new Unit("ml", "毫升", "mL", 1e-6));
        volume.add(new Unit("cl", "厘升", "cL", 1e-5));
        volume.add(new Unit("dl", "分升", "dL", 1e-4));
        volume.add(new Unit("l", "升", "L", 0.001));
        volume.add(new Unit("hl", "公石", "hL", 0.1));
        volume.add(new Unit("mm3", "立方毫米", "mm³", 1e-9));
        volume.add(new Unit("cm3", "立方厘米", "cm³", 1e-6));
        volume.add(new Unit("dm3", "立方分米", "dm³", 0.001));
        volume.add(new Unit("m3", "立方米", "m³", 1.0));
        volume.add(new Unit("in3", "立方英寸", "in³", 1.6387064e-5));
        volume.add(new Unit("ft3", "立方英尺", "ft³", 0.028316846592));
        volume.add(new Unit("yd3", "立方码", "yd³", 0.764554857984));
        volume.add(new Unit("gal_us", "美制加仑", "gal(US)", 0.003785411784));
        volume.add(new Unit("gal_uk", "英制加仑", "gal(UK)", 0.00454609));
        volume.add(new Unit("qt_us", "美制夸脱", "qt(US)", 0.000946352946));
        volume.add(new Unit("floz_us", "美制液体盎司", "oz(US)", 2.95735295625e-5));
        volume.add(new Unit("floz_uk", "英制液体盎司", "oz(UK)", 2.84130625e-5));
        volume.add(new Unit("cup", "美制杯", "cup", 0.0002365882365));
        volume.add(new Unit("acft", "亩英尺", "ac·ft", 1233.48183754752));
        VOLUME = new Category("volume", "体积", "m³", false, volume);

        List<Unit> speed = new ArrayList<>();
        speed.add(new Unit("mps", "米每秒", "m/s", 1.0));
        speed.add(new Unit("kmh", "千米每小时", "km/h", 1000.0 / 3600.0));
        speed.add(new Unit("kmps", "千米每秒", "km/s", 1000.0));
        speed.add(new Unit("mph", "英里每小时", "mi/h", 1609.344 / 3600.0));
        speed.add(new Unit("ips", "英寸每秒", "in/s", 0.0254));
        speed.add(new Unit("kn", "节", "kn", 1852.0 / 3600.0));
        speed.add(new Unit("fps", "英尺每秒", "ft/s", 0.3048));
        speed.add(new Unit("mach", "马赫（海平面 15°C）", "Ma", 340.3));
        speed.add(new Unit("c", "光速", "c", 299792458.0));
        SPEED = new Category("speed", "速度", "m/s", false, speed);

        List<Unit> time = new ArrayList<>();
        time.add(new Unit("ns", "纳秒", "ns", 1e-9));
        time.add(new Unit("us", "微秒", "μs", 1e-6));
        time.add(new Unit("ms", "毫秒", "ms", 0.001));
        time.add(new Unit("s", "秒", "s", 1.0));
        time.add(new Unit("min", "分钟", "min", 60.0));
        time.add(new Unit("h", "小时", "h", 3600.0));
        time.add(new Unit("d", "天", "d", 86400.0));
        time.add(new Unit("wk", "周", "week", 604800.0));
        time.add(new Unit("yr", "年（365 天）", "yr", 31536000.0));
        TIME = new Category("time", "时间", "s", false, time);

        List<Unit> pressure = new ArrayList<>();
        pressure.add(new Unit("pa", "帕斯卡", "Pa", 1.0));
        pressure.add(new Unit("hpa", "百帕", "hPa", 100.0));
        pressure.add(new Unit("kpa", "千帕", "kPa", 1000.0));
        pressure.add(new Unit("mpa", "兆帕", "MPa", 1e6));
        pressure.add(new Unit("bar", "巴", "bar", 100000.0));
        pressure.add(new Unit("mbar", "毫巴", "mbar", 100.0));
        pressure.add(new Unit("atm", "标准大气压", "atm", 101325.0));
        pressure.add(new Unit("mmhg", "毫米汞柱", "mmHg", 133.322387415));
        pressure.add(new Unit("inhg", "英寸汞柱", "inHg", 3386.389));
        pressure.add(new Unit("mmh2o", "毫米水柱", "mmH₂O", 9.80665));
        pressure.add(new Unit("psi", "磅力每平方英寸", "psi", 6894.757293168));
        pressure.add(new Unit("psf", "磅力每平方英尺", "psf", 47.88025898033584));
        pressure.add(new Unit("kgfcm2", "公斤力每平方厘米", "kgf/cm²", 98066.5));
        pressure.add(new Unit("kgfm2", "公斤力每平方米", "kgf/m²", 9.80665));
        PRESSURE = new Category("pressure", "压力", "Pa", false, pressure);

        List<Unit> energy = new ArrayList<>();
        energy.add(new Unit("j", "焦耳", "J", 1.0));
        energy.add(new Unit("kj", "千焦", "kJ", 1000.0));
        energy.add(new Unit("cal", "卡", "cal", 4.184));
        energy.add(new Unit("kcal", "千卡（大卡）", "kcal", 4184.0));
        energy.add(new Unit("wh", "瓦时", "Wh", 3600.0));
        energy.add(new Unit("kwh", "千瓦时（度）", "kW·h", 3.6e6));
        energy.add(new Unit("btu", "英热单位", "BTU", 1055.05585262));
        energy.add(new Unit("ev", "电子伏", "eV", 1.602176634e-19));
        energy.add(new Unit("kgm", "公斤·米", "kg·m", 9.80665));
        energy.add(new Unit("ftlb", "英尺·磅", "ft·lb", 1.3558179483314004));
        energy.add(new Unit("hph", "英制马力·时", "hp·h", 2684519.5376962));
        energy.add(new Unit("psh", "米制马力·时", "PS·h", 2647795.5));
        ENERGY = new Category("energy", "功/能/热", "J", false, energy);

        List<Unit> power = new ArrayList<>();
        power.add(new Unit("mw", "毫瓦", "mW", 0.001));
        power.add(new Unit("w", "瓦", "W", 1.0));
        power.add(new Unit("kw", "千瓦", "kW", 1000.0));
        power.add(new Unit("js", "焦耳每秒", "J/s", 1.0));
        power.add(new Unit("nms", "牛顿·米每秒", "N·m/s", 1.0));
        power.add(new Unit("kgms", "公斤·米每秒", "kg·m/s", 9.80665));
        power.add(new Unit("kcals", "千卡每秒", "kcal/s", 4184.0));
        power.add(new Unit("btus", "英热单位每秒", "BTU/s", 1055.05585262));
        power.add(new Unit("btuh", "英热单位每小时", "BTU/h", 0.29307107017));
        power.add(new Unit("ftlbs", "英尺·磅每秒", "ft·lb/s", 1.3558179483314004));
        power.add(new Unit("hp", "英制马力", "hp", 745.6998715823));
        power.add(new Unit("ps", "米制马力", "PS", 735.49875));
        POWER = new Category("power", "功率", "W", false, power);

        List<Unit> force = new ArrayList<>();
        force.add(new Unit("dyn", "达因", "dyn", 1e-5));
        force.add(new Unit("n", "牛顿", "N", 1.0));
        force.add(new Unit("kn2", "千牛", "kN", 1000.0));
        force.add(new Unit("gf", "克力", "gf", 0.00980665));
        force.add(new Unit("kgf", "千克力（公斤力）", "kgf", 9.80665));
        force.add(new Unit("tf", "公吨力", "tf", 9806.65));
        force.add(new Unit("lbf", "磅力", "lbf", 4.4482216152605));
        force.add(new Unit("kip", "千磅力", "kip", 4448.2216152605));
        FORCE = new Category("force", "力/能源", "N", false, force);

        List<Unit> density = new ArrayList<>();
        density.add(new Unit("kgm3", "千克每立方米", "kg/m³", 1.0));
        density.add(new Unit("kgdm3", "千克每立方分米", "kg/dm³", 1000.0));
        density.add(new Unit("kgcm3", "千克每立方厘米", "kg/cm³", 1e6));
        density.add(new Unit("gcm3", "克每立方厘米", "g/cm³", 1000.0));
        density.add(new Unit("gdm3", "克每立方分米", "g/dm³", 1.0));
        density.add(new Unit("gm3", "克每立方米", "g/m³", 0.001));
        density.add(new Unit("kgL", "千克每升", "kg/L", 1000.0));
        density.add(new Unit("lbft3", "磅每立方英尺", "lb/ft³", 16.01846337396));
        density.add(new Unit("lbgal", "磅每美制加仑", "lb/gal", 119.8264273169));
        DENSITY = new Category("density", "密度", "kg/m³", false, density);

        List<Category> extended = new ArrayList<>();
        extended.add(ANGLE);
        extended.add(DATA);
        extended.add(AREA);
        extended.add(VOLUME);
        extended.add(SPEED);
        extended.add(TIME);
        extended.add(PRESSURE);
        extended.add(ENERGY);
        extended.add(POWER);
        extended.add(FORCE);
        extended.add(DENSITY);
        EXTENDED = Collections.unmodifiableList(extended);
    }

    /** 066 扩展出来的全部类别（不含长度/重量/温度，那三个在 ALL 里）。 */
    public static final List<Category> EXTENDED;

    /** 全部类别：基础 3 + 扩展 11。 */
    public static List<Category> everything() {
        List<Category> all = new ArrayList<>(ALL);
        all.addAll(EXTENDED);
        return all;
    }

    /** 在全部类别里按 id 查找（含扩展）。 */
    public static Category anyCategory(String id) {
        Category c = category(id);
        if (c != null) {
            return c;
        }
        for (Category e : EXTENDED) {
            if (e.id.equals(id)) {
                return e;
            }
        }
        return null;
    }

    private UnitTool() {
    }

    public static Category category(String id) {
        for (Category c : ALL) {
            if (c.id.equals(id)) {
                return c;
            }
        }
        return null;
    }

    /** 换算主入口；结果用 BigDecimal 收敛到合理有效位，避免 1 米 = 1000.0000000000001 毫米这种浮点噪声。 */
    public static BigDecimal convert(Category cat, Unit from, Unit to, double value) throws ToolException {
        if (cat == null || from == null || to == null) {
            throw new ToolException("请先选择要换算的两个单位。");
        }
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            throw new ToolException("输入不是有限的数字。请填写一个普通数值，例如 12.5。");
        }
        double result;
        if (cat.affine) {
            double celsius = toCelsius(from.id, value);
            result = fromCelsius(to.id, celsius);
        } else {
            result = value * from.factor / to.factor;
        }
        return round(result);
    }

    /** 低于绝对零度在物理上没有意义，单独判断并给出人话错误。 */
    public static void checkPhysical(Category cat, Unit unit, double value) throws ToolException {
        if (cat.affine) {
            double celsius = toCelsius(unit.id, value);
            if (celsius < -273.15 - 1e-9) {
                throw new ToolException("低于绝对零度（" + format(round(-273.15)) + " °C），这个温度不存在。");
            }
        } else if (value < 0) {
            throw new ToolException("长度和重量不接受负数。");
        }
    }

    private static double toCelsius(String unitId, double v) {
        if ("f".equals(unitId)) {
            return (v - 32.0) * 5.0 / 9.0;
        }
        if ("k".equals(unitId)) {
            return v - 273.15;
        }
        if ("re".equals(unitId)) {
            // 列氏度：0 °Re = 0 °C，80 °Re = 100 °C
            return v * 1.25;
        }
        if ("ra".equals(unitId)) {
            // 兰氏度：0 °R = 绝对零度
            return (v - 491.67) * 5.0 / 9.0;
        }
        return v;
    }

    private static double fromCelsius(String unitId, double c) {
        if ("f".equals(unitId)) {
            return c * 9.0 / 5.0 + 32.0;
        }
        if ("k".equals(unitId)) {
            return c + 273.15;
        }
        if ("re".equals(unitId)) {
            return c * 0.8;
        }
        if ("ra".equals(unitId)) {
            return (c + 273.15) * 9.0 / 5.0;
        }
        return c;
    }

    /**
     * 保留 12 位有效数字再去掉尾随零。
     * 12 位是刻意选的：足以消除二进制浮点误差，又不会展示毫无意义的噪声位。
     */
    public static BigDecimal round(double value) {
        if (value == 0.0) {
            return BigDecimal.ZERO;
        }
        BigDecimal bd = new BigDecimal(value, new MathContext(12, RoundingMode.HALF_UP));
        return bd.stripTrailingZeros();
    }

    /** 去掉科学计数法尾巴，输出人类可读的数值。 */
    public static String format(BigDecimal value) {
        if (value.signum() == 0) {
            return "0";
        }
        BigDecimal abs = value.abs();
        if (abs.compareTo(new BigDecimal("0.000001")) < 0
                || abs.compareTo(new BigDecimal("1000000000000")) > 0) {
            // 极小或极大：用普通记数法仍可读时优先普通记数法，否则退到科学计数法。
            String plain = value.toPlainString();
            if (plain.length() <= 24) {
                return plain;
            }
            return value.round(new MathContext(6, RoundingMode.HALF_UP)).toString();
        }
        return value.toPlainString();
    }

    /** 解析用户输入的数字，给出具体错误。 */
    public static double parseNumber(String text, String fieldLabel) throws ToolException {
        if (text == null || text.trim().isEmpty()) {
            throw new ToolException(fieldLabel + "没有填写。请输入一个数值，例如 12.5。");
        }
        String s = text.trim().replace("，", "").replace(",", "").replace(" ", "");
        try {
            double v = Double.parseDouble(s);
            if (Double.isNaN(v) || Double.isInfinite(v)) {
                throw new NumberFormatException("not finite");
            }
            return v;
        } catch (NumberFormatException e) {
            throw new ToolException(fieldLabel + "「" + text.trim() + "」不是数字。"
                    + "可以用小数、负号和科学计数法，例如 12.5、-3、1e3。");
        }
    }
}
