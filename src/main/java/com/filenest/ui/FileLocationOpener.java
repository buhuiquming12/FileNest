package com.filenest.ui;

import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Opens the closest existing folder for a cleanup suggestion. */
final class FileLocationOpener {
    void open(Path target) throws IOException {
        Path folder = containingFolder(target);
        if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
            throw new IOException("当前系统不支持打开文件夹");
        }
        Desktop.getDesktop().open(folder.toFile());
    }

    static Path containingFolder(Path target) throws IOException {
        if (target == null) throw new IOException("建议路径为空");
        Path current = target.toAbsolutePath().normalize();
        if (!Files.isDirectory(current)) current = current.getParent();
        while (current != null && !Files.isDirectory(current)) current = current.getParent();
        if (current == null) throw new IOException("找不到可打开的文件夹: " + target);
        return current;
    }
}
