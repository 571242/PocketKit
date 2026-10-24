package cn.xianhua.pocketkit.media;

import java.util.HashMap;
import java.util.Map;

/**
 * 中文对照表 for the ML Kit image-labeling English labels.
 *
 * <p>Only the common labels are translated. An untranslated label is shown in English alone — a
 * wrong translation would be worse than none, so this table contains only entries that are
 * unambiguous. It is not a translation service and does not pretend to be one.
 */
final class LabelZh {

    private static final Map<String, String> TABLE = new HashMap<>();

    private LabelZh() {
    }

    private static void put(String en, String zh) {
        TABLE.put(en, zh);
    }

    static String lookup(String english) {
        if (english == null) {
            return null;
        }
        return TABLE.get(english);
    }

    static {
        put("Food", "食物");
        put("Dish", "菜肴");
        put("Cuisine", "料理");
        put("Meal", "一餐");
        put("Breakfast", "早餐");
        put("Lunch", "午餐");
        put("Dinner", "晚餐");
        put("Dessert", "甜点");
        put("Snack", "零食");
        put("Fruit", "水果");
        put("Vegetable", "蔬菜");
        put("Drink", "饮料");
        put("Coffee", "咖啡");
        put("Tea", "茶");
        put("Wine", "葡萄酒");
        put("Beer", "啤酒");
        put("Cocktail", "鸡尾酒");
        put("Juice", "果汁");
        put("Bread", "面包");
        put("Cake", "蛋糕");
        put("Pizza", "披萨");
        put("Sushi", "寿司");
        put("Noodle", "面条");
        put("Rice", "米饭");
        put("Soup", "汤");
        put("Salad", "沙拉");
        put("Sandwich", "三明治");
        put("Burger", "汉堡");
        put("Cheese", "奶酪");
        put("Egg", "蛋");
        put("Meat", "肉");
        put("Seafood", "海鲜");
        put("Fish", "鱼");
        put("Chicken", "鸡肉");

        put("Animal", "动物");
        put("Pet", "宠物");
        put("Dog", "狗");
        put("Cat", "猫");
        put("Bird", "鸟");
        put("Horse", "马");
        put("Cow", "牛");
        put("Sheep", "绵羊");
        put("Insect", "昆虫");
        put("Butterfly", "蝴蝶");
        put("Flower", "花");
        put("Plant", "植物");
        put("Tree", "树");
        put("Grass", "草");
        put("Leaf", "叶子");
        put("Forest", "森林");
        put("Garden", "花园");
        put("Nature", "自然");
        put("Sky", "天空");
        put("Cloud", "云");
        put("Sunset", "日落");
        put("Sunrise", "日出");
        put("Beach", "海滩");
        put("Sea", "海");
        put("Ocean", "海洋");
        put("Lake", "湖");
        put("River", "河流");
        put("Mountain", "山");
        put("Snow", "雪");
        put("Rain", "雨");
        put("Rainbow", "彩虹");

        put("Person", "人");
        put("People", "人们");
        put("Portrait", "肖像");
        put("Face", "脸");
        put("Smile", "笑容");
        put("Child", "小孩");
        put("Baby", "婴儿");
        put("Man", "男性");
        put("Woman", "女性");
        put("Family", "家庭");
        put("Crowd", "人群");
        put("Selfie", "自拍");
        put("Human body", "人体");

        put("Car", "汽车");
        put("Vehicle", "交通工具");
        put("Truck", "卡车");
        put("Bus", "公交车");
        put("Motorcycle", "摩托车");
        put("Bicycle", "自行车");
        put("Boat", "船");
        put("Ship", "轮船");
        put("Airplane", "飞机");
        put("Helicopter", "直升机");
        put("Train", "火车");
        put("Road", "道路");
        put("Traffic", "交通");
        put("Transport", "运输");

        put("Building", "建筑");
        put("House", "房屋");
        put("Architecture", "建筑艺术");
        put("Room", "房间");
        put("Interior design", "室内设计");
        put("Furniture", "家具");
        put("Chair", "椅子");
        put("Table", "桌子");
        put("Bed", "床");
        put("Window", "窗户");
        put("Door", "门");
        put("Wall", "墙");
        put("Floor", "地板");
        put("Kitchen", "厨房");
        put("Bathroom", "浴室");
        put("Office", "办公室");
        put("School", "学校");
        put("Church", "教堂");
        put("Bridge", "桥");
        put("Tower", "塔");
        put("Factory", "工厂");
        put("City", "城市");
        put("Street", "街道");
        put("Landmark", "地标");

        put("Computer", "电脑");
        put("Laptop", "笔记本电脑");
        put("Mobile phone", "手机");
        put("Telephone", "电话");
        put("Tablet computer", "平板电脑");
        put("Keyboard", "键盘");
        put("Mouse", "鼠标");
        put("Monitor", "显示器");
        put("Camera", "相机");
        put("Television", "电视");
        put("Headphones", "耳机");
        put("Speaker", "音箱");
        put("Watch", "手表");
        put("Clock", "钟表");
        put("Book", "书");
        put("Paper", "纸");
        put("Pen", "笔");
        put("Pencil", "铅笔");
        put("Bag", "包");
        put("Shoe", "鞋");
        put("Hat", "帽子");
        put("Glasses", "眼镜");
        put("Jewellery", "珠宝");
        put("Toy", "玩具");
        put("Ball", "球");
        put("Guitar", "吉他");
        put("Musical instrument", "乐器");
        put("Bottle", "瓶子");
        put("Cup", "杯子");
        put("Tool", "工具");
        put("Machine", "机器");
        put("Money", "钱");
        put("Document", "文件");
        put("Text", "文字");
        put("Sign", "标志牌");
        put("Map", "地图");
        put("Poster", "海报");
        put("Painting", "绘画");
        put("Art", "艺术");
        put("Sculpture", "雕塑");
        put("Pattern", "图案");
        put("Design", "设计");
        put("Logo", "标志");
        put("Graphic design", "平面设计");
        put("Font", "字体");
        put("Screenshot", "截图");
        put("Circle", "圆形");
        put("Line", "线条");

        put("Sport", "运动");
        put("Sports equipment", "运动器械");
        put("Football", "足球");
        put("Basketball", "篮球");
        put("Tennis", "网球");
        put("Swimming", "游泳");
        put("Running", "跑步");
        put("Cycling", "骑行");
        put("Gym", "健身房");
        put("Yoga", "瑜伽");
        put("Dance", "舞蹈");
        put("Game", "游戏");

        put("Event", "活动");
        put("Wedding", "婚礼");
        put("Party", "聚会");
        put("Concert", "演唱会");
        put("Festival", "节日");
        put("Fireworks", "烟花");
        put("Holiday", "假期");
        put("Travel", "旅行");
        put("Vacation", "度假");

        put("Light", "光");
        put("Shadow", "阴影");
        put("Reflection", "倒影");
        put("Night", "夜晚");
        put("Darkness", "黑暗");
        put("Monochrome", "单色");
        put("Black-and-white", "黑白");
        put("Colorfulness", "色彩丰富");
        put("Photography", "摄影");
        put("Snapshot", "快照");

        put("Clothing", "服装");
        put("Dress", "连衣裙");
        put("Shirt", "衬衫");
        put("Jeans", "牛仔裤");
        put("Uniform", "制服");
        put("Textile", "纺织品");
        put("Wood", "木材");
        put("Metal", "金属");
        put("Plastic", "塑料");
        put("Glass", "玻璃");
        put("Stone", "石头");
        put("Concrete", "混凝土");
        put("Ceramic", "陶瓷");
        put("Leather", "皮革");
    }
}
