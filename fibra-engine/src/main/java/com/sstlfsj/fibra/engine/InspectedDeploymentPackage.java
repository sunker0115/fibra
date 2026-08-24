package com.sstlfsj.fibra.engine;

import java.nio.file.Path;
import java.util.List;

record InspectedDeploymentPackage(String id, String version, String sha256,
                                  Path workspace, Path configPath,
                                  List<Path> pluginPaths) { }
