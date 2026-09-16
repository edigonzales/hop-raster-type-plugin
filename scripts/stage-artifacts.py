from pathlib import Path
import shutil
root=Path(__file__).resolve().parents[1]
out=root/"target/publication"
out.mkdir(parents=True,exist_ok=True)
for module in ("hop-raster-core","hop-raster-type","hop-raster-geotools"):
    shutil.copy2(root/module/"pom.xml",out/(module+".pom"))
    for jar in (root/module/"target").glob(module+"-*.jar"):
        if not jar.name.endswith(("-sources.jar","-javadoc.jar")):shutil.copy2(jar,out/jar.name)
