package com.sstlfsj.fibra.engine;

public sealed interface EngineCommand permits ApplyDeployment, ReconcileCurrent { }
