public class A {
    // 实验1：方法签名引用 B（类加载时是否解析？）
    public B helperSig() { return null; }
    // 实验2：方法体 checkcast 引用 B（类加载时是否解析？）
    public void helperBody() {
        Object o = null;
        if (o != null) { B b = (B) o; }
    }
    public static void main(String[] args) {
        System.out.println("main started");
        try {
            Class<?> c = Class.forName("A");
            System.out.println("A loaded OK");
        } catch (Throwable t) {
            System.out.println("A load FAILED: " + t);
        }
    }
}
