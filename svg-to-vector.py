"""Compile the app's outline SVG icons to Android VectorDrawables."""

from pathlib import Path
import sys
from xml.etree import ElementTree as ET


ANDROID = "http://schemas.android.com/apk/res/android"
SVG = "{http://www.w3.org/2000/svg}"
ET.register_namespace("android", ANDROID)


def android_attrs(**attrs):
    return {f"{{{ANDROID}}}{name}": value for name, value in attrs.items()}


def outline(element):
    if element.tag == SVG + "path":
        return element.attrib["d"]
    if element.tag == SVG + "line":
        a = element.attrib
        return f"M{a['x1']},{a['y1']} L{a['x2']},{a['y2']}"
    if element.tag == SVG + "rect":
        a = element.attrib
        x, y, width, height = (float(a[key]) for key in ("x", "y", "width", "height"))
        rx = float(a.get("rx", a.get("ry", 0)))
        ry = float(a.get("ry", a.get("rx", 0)))
        right, bottom = x + width, y + height
        if not rx or not ry:
            return f"M{x:g},{y:g} H{right:g} V{bottom:g} H{x:g} Z"
        arc = f"A{rx:g},{ry:g} 0 0,1"
        return (f"M{x+rx:g},{y:g} H{right-rx:g} {arc} {right:g},{y+ry:g} "
                f"V{bottom-ry:g} {arc} {right-rx:g},{bottom:g} H{x+rx:g} "
                f"{arc} {x:g},{bottom-ry:g} V{y+ry:g} {arc} {x+rx:g},{y:g} Z")
    if element.tag == SVG + "circle":
        x, y, radius = (float(element.attrib[key]) for key in ("cx", "cy", "r"))
        return (
            f"M{x-radius:g},{y:g} A{radius:g},{radius:g} 0 1,0 {x+radius:g},{y:g} "
            f"A{radius:g},{radius:g} 0 1,0 {x-radius:g},{y:g} Z"
        )
    raise ValueError(f"Unsupported icon element: {element.tag}")


def compile_icon(source, destination):
    svg = ET.parse(source).getroot()
    x, y, width, height = svg.attrib["viewBox"].split()
    fill = svg.attrib["fill"]
    if fill not in {"none", "currentColor"}:
        raise ValueError(f"Expected a monochrome icon: {source}")
    vector = ET.Element("vector", android_attrs(width="24dp", height="24dp",
                                               viewportWidth=width, viewportHeight=height))
    shapes = vector
    if float(x) or float(y):
        shapes = ET.SubElement(vector, "group", android_attrs(translateX=f"{-float(x):g}", translateY=f"{-float(y):g}"))
    style = android_attrs(fillColor="#FFFFFFFF") if fill == "currentColor" else android_attrs(
        strokeColor="#FFFFFFFF", strokeWidth=svg.attrib["stroke-width"], strokeLineCap=svg.attrib["stroke-linecap"],
        strokeLineJoin=svg.attrib["stroke-linejoin"],
    )
    for element in svg:
        ET.SubElement(shapes, "path", android_attrs(pathData=outline(element)) | style)
    ET.indent(vector)
    name = f"ic_{source.stem.replace('-', '_')}.xml"
    ET.ElementTree(vector).write(destination / name, encoding="utf-8", xml_declaration=True)


if __name__ == "__main__":
    source_dir, output_dir = map(Path, sys.argv[1:])
    output_dir.mkdir(parents=True, exist_ok=True)
    for icon in sorted(source_dir.glob("*.svg")):
        compile_icon(icon, output_dir)
