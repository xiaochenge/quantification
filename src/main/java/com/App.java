package com;

/** 入口类。IDEA 里点左边那个绿色三角就能运行。 */
public class App {

    public static void main(String[] args) {
        System.out.println("quantification 跑起来了");
        System.out.println("Java 版本：" + System.getProperty("java.version"));
    }

    /** 示例方法：算一组数的平均值，测试类里会验证它。 */
    public static double average(double... values) {
        if (values.length == 0) {
            throw new IllegalArgumentException("至少要有一个数");
        }
        double sum = 0;
        for (double value : values) {
            sum += value;
        }
        return sum / values.length;
    }
}
