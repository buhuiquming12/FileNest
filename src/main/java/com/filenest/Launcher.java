package com.filenest;

/**
 * 普通启动入口（<b>不</b>继承 {@link javafx.application.Application}）。
 *
 * <p>为什么需要它：如果直接用 {@code java} 启动器运行一个继承了 {@code Application} 的类
 * （例如在 IDE 里点绿色运行按钮，或 {@code java com.filenest.App}），JDK 会检查
 * {@code javafx.graphics} 是否在 <b>module path</b> 上，否则报：
 * <pre>错误: 缺少 JavaFX 运行时组件, 需要使用该组件来运行此应用程序</pre>
 *
 * <p>本类不继承 {@code Application}，JDK 便跳过该检查；它再调用
 * {@link App#main(String[])}（内部 {@code Application.launch(...)}），此时 JavaFX
 * 从 classpath 正常加载。
 *
 * <p><b>运行方式（任选其一）：</b>
 * <ul>
 *   <li>命令行：{@code mvn javafx:run}</li>
 *   <li>IDE：运行本类 {@code com.filenest.Launcher}（不要直接运行 {@code App}）</li>
 * </ul>
 */
public final class Launcher {

    public static void main(String[] args) {
        App.main(args);
    }
}
