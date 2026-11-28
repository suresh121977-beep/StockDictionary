pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositories { google(); mavenCentral(); maven("https://jitpack.io") } }
rootProject.name = "StockDictionary"
include(":host")
include(":TradingHub_project_app")
project(":TradingHub_project_app").projectDir = file("modules/TradingHub_project_app")
