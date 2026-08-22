package com.sstlfsj.fibra.loader.pf4j;

import java.nio.file.attribute.FileTime;

record FibraPluginCandidate(String pluginId, String version, FileTime modifiedAt) {
}
