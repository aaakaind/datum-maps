"""Generate the Android app's vector drawables: launcher icon, turn-by-turn
maneuver arrows, and the car screen's action and category icons.

  python3 tools/make_android_icons.py android/app/src/main/res

Everything is drawn from simple geometry here, in the same flat, heavy-stroked
style as the web app, so there are no third-party icon sets to license.
"""
import math
import sys
from pathlib import Path

BONE, INK, RED, WHITE = "#F3EEE4", "#1C1A17", "#C8432B", "#FFFFFF"
NS = 'xmlns:android="http://schemas.android.com/apk/res/android"'


def vector(paths, size=48, dp=48):
    body = "\n".join(paths)
    return (f'<?xml version="1.0" encoding="utf-8"?>\n<vector {NS}\n    android:width="{dp}dp" android:height="{dp}dp"\n'
            f'    android:viewportWidth="{size}" android:viewportHeight="{size}">\n{body}\n</vector>\n')


def stroke(d, w=5.0, color=WHITE, alpha=1.0, cap="round", join="round"):
    a = f' android:strokeAlpha="{alpha}"' if alpha < 1 else ""
    return (f'    <path android:pathData="{d}" android:strokeColor="{color}" android:strokeWidth="{w}"'
            f' android:strokeLineCap="{cap}" android:strokeLineJoin="{join}"{a}/>')


def fill(d, color=WHITE, alpha=1.0):
    a = f' android:fillAlpha="{alpha}"' if alpha < 1 else ""
    return f'    <path android:pathData="{d}" android:fillColor="{color}"{a}/>'


def f(x):
    return f"{x:.2f}".rstrip("0").rstrip(".")


def head(x, y, ang, size=8.5):
    """Filled arrowhead with its tip at (x, y), pointing along ang (degrees, 0 = up, + = clockwise)."""
    r = math.radians(ang)
    dx, dy = math.sin(r), -math.cos(r)
    bx, by = x - dx * size, y - dy * size
    px, py = -dy * size * 0.62, dx * size * 0.62
    return fill(f"M{f(x)},{f(y)} L{f(bx + px)},{f(by + py)} L{f(bx - px)},{f(by - py)} Z")


def turn(ang, alpha_other=None):
    """Stem from the bottom to a junction, then a branch at `ang` with an arrowhead."""
    jx, jy = 24, 27
    L = 15 if abs(ang) < 100 else 12
    r = math.radians(ang)
    ex, ey = jx + math.sin(r) * L, jy - math.cos(r) * L
    tip_x, tip_y = jx + math.sin(r) * (L + 6), jy - math.cos(r) * (L + 6)
    out = []
    if alpha_other is not None:
        out.append(stroke(f"M{jx},{jy} L{jx},8", alpha=alpha_other))
    out += [stroke(f"M24,44 L{jx},{jy} L{f(ex)},{f(ey)}"), head(tip_x, tip_y, ang)]
    return out


def mirror(paths):
    return ['    <group android:pivotX="24" android:scaleX="-1">'] + ["    " + p for p in paths] + ["    </group>"]


def maneuvers():
    m = {}
    m["straight"] = [stroke("M24,44 L24,14"), head(24, 6, 0)]
    for name, ang in (("slight", 45), ("turn", 90), ("sharp", 135)):
        m[f"{name}_right"] = turn(ang)
        m[f"{name}_left"] = mirror(turn(ang))
    m["keep_right"] = turn(32, alpha_other=0.35)
    m["keep_left"] = mirror(turn(32, alpha_other=0.35))
    uturn = [stroke("M30,44 L30,20 A7,7 0 0,0 16,20 L16,28"), head(16, 37, 180)]
    m["uturn_left"] = uturn
    m["uturn_right"] = mirror(uturn)
    m["merge"] = [stroke("M24,44 L24,14"), stroke("M12,44 L12,36 Q12,30 24,24", alpha=0.45), head(24, 6, 0)]
    # Counter-clockwise, as in right-hand traffic: enter at the bottom, exit top right.
    m["roundabout"] = [stroke("M24,24 m-9,0 a9,9 0 1,0 18,0 a9,9 0 1,0 -18,0", w=3, alpha=0.35),
                       stroke("M24,44 L24,33 A9,9 0 0,0 30.36,17.64 L34,14", w=4.5), head(38.2, 9.8, 45)]
    m["depart"] = [stroke("M24,36 L24,16"), head(24, 8, 0), fill("M24,38.5 m-4.5,0 a4.5,4.5 0 1,0 9,0 a4.5,4.5 0 1,0 -9,0")]
    pin = [fill("M24,4 C15.7,4 10,10.2 10,18 C10,28 24,44 24,44 C24,44 38,28 38,18 C38,10.2 32.3,4 24,4 Z"),
           fill("M24,18 m-5.5,0 a5.5,5.5 0 1,0 11,0 a5.5,5.5 0 1,0 -11,0", color=INK)]
    m["destination"] = pin
    m["ferry"] = [fill("M8,28 L40,28 L35,37 L13,37 Z"), stroke("M17,28 L17,20 L31,20 L31,28", w=4),
                  stroke("M6,42 Q10,39 14,42 T22,42 T30,42 T38,42 T44,42", w=3)]
    return {f"ic_mnv_{k}": vector(v) for k, v in m.items()}


def actions():
    a = {
        "search": [stroke("M10.5,10.5 m-6,0 a6,6 0 1,0 12,0 a6,6 0 1,0 -12,0", w=2.4), stroke("M15,15 L20.5,20.5", w=2.6)],
        "recenter": [stroke("M12,12 m-6.5,0 a6.5,6.5 0 1,0 13,0 a6.5,6.5 0 1,0 -13,0", w=2.2),
                     fill("M12,12 m-2.6,0 a2.6,2.6 0 1,0 5.2,0 a2.6,2.6 0 1,0 -5.2,0"),
                     stroke("M12,1.5 L12,4.5 M12,19.5 L12,22.5 M1.5,12 L4.5,12 M19.5,12 L22.5,12", w=2.2)],
        "go": [fill("M12,2.5 L20,21 L12,16.5 L4,21 Z")],
        "zoom_in": [stroke("M12,4.5 L12,19.5 M4.5,12 L19.5,12", w=2.6)],
        "zoom_out": [stroke("M4.5,12 L19.5,12", w=2.6)],
        "stop": [stroke("M6,6 L18,18 M18,6 L6,18", w=2.6)],
        "overview": [stroke("M6,19 C6,12 18,13 18,5", w=2.4), fill("M6,19 m-2.6,0 a2.6,2.6 0 1,0 5.2,0 a2.6,2.6 0 1,0 -5.2,0"),
                     fill("M18,5 m-2.6,0 a2.6,2.6 0 1,0 5.2,0 a2.6,2.6 0 1,0 -5.2,0")],
        "volume_on": [fill("M3,9 L7,9 L12,4.5 L12,19.5 L7,15 L3,15 Z"), stroke("M15.5,8.5 Q18,12 15.5,15.5", w=2),
                      stroke("M18.5,5.5 Q23,12 18.5,18.5", w=2)],
        "volume_off": [fill("M3,9 L7,9 L12,4.5 L12,19.5 L7,15 L3,15 Z"), stroke("M15.5,9 L21,15 M21,9 L15.5,15", w=2.2)],
        "fuel": [stroke("M5,21 L5,5 Q5,3 7,3 L13,3 Q15,3 15,5 L15,21 M3,21 L17,21", w=2.2), fill("M7,6 L13,6 L13,10 L7,10 Z"),
                 stroke("M15,9 L18,11 L18,17 Q18,19 19.5,19 Q21,19 21,17 L21,8 L18.5,5.5", w=1.8)],
        "ev": [fill("M13.5,2 L5,13.5 L11,13.5 L10,22 L19,10 L13,10 Z")],
        "parking": [stroke("M8,21 L8,3 L13.5,3 Q19,3 19,8.25 Q19,13.5 13.5,13.5 L8,13.5", w=2.8, cap="butt", join="miter")],
        "food": [stroke("M7,2.5 L7,21.5 M4,2.5 L4,7.5 Q4,10 7,10 Q10,10 10,7.5 L10,2.5", w=2),
                 stroke("M17,21.5 L17,2.5 Q13.5,4.5 13.5,10 L13.5,13 L17,13", w=2)],
        "coffee": [stroke("M4,8 L16,8 L16,15 Q16,20 10,20 Q4,20 4,15 Z M16,10 L18,10 Q20.5,10 20.5,12.5 Q20.5,15 18,15 L16,15", w=2),
                   stroke("M7,2.5 Q8.5,4 7,5.5 M11,2.5 Q12.5,4 11,5.5", w=1.6)],
        "recent": [stroke("M12,12 m-8.5,0 a8.5,8.5 0 1,0 17,0 a8.5,8.5 0 1,0 -17,0", w=2.2), stroke("M12,6.5 L12,12 L16,14.5", w=2.2)],
        "place": [fill("M12,2 C8,2 5,5 5,9 C5,14 12,22 12,22 C12,22 19,14 19,9 C19,5 16,2 12,2 Z M12,6.5 a2.5,2.5 0 1,1 0,5 a2.5,2.5 0 1,1 0,-5 Z")],
    }
    return {f"ic_car_{k}": vector(v, 24, 24) for k, v in a.items()}


def launcher():
    # Adaptive icon: the web app's favicon, a red datum point in an ink ring on bone.
    fg = vector([f'    <path android:pathData="M54,54 m-26,0 a26,26 0 1,0 52,0 a26,26 0 1,0 -52,0"'
                 f' android:fillColor="{RED}" android:strokeColor="{INK}" android:strokeWidth="8"/>'], 108, 108)
    mono = vector([f'    <path android:pathData="M54,54 m-26,0 a26,26 0 1,0 52,0 a26,26 0 1,0 -52,0"'
                   f' android:fillColor="#00000000" android:strokeColor="#FF000000" android:strokeWidth="9"/>',
                   '    <path android:pathData="M54,54 m-11,0 a11,11 0 1,0 22,0 a11,11 0 1,0 -22,0" android:fillColor="#FF000000"/>'], 108, 108)
    notif = vector([stroke("M12,12 m-7.5,0 a7.5,7.5 0 1,0 15,0 a7.5,7.5 0 1,0 -15,0", w=2.6),
                    fill("M12,12 m-3.6,0 a3.6,3.6 0 1,0 7.2,0 a3.6,3.6 0 1,0 -7.2,0")], 24, 24)
    adaptive = (f'<?xml version="1.0" encoding="utf-8"?>\n<adaptive-icon {NS}>\n'
                '    <background android:drawable="@color/datum_bone"/>\n'
                '    <foreground android:drawable="@drawable/ic_launcher_foreground"/>\n'
                '    <monochrome android:drawable="@drawable/ic_launcher_monochrome"/>\n</adaptive-icon>\n')
    return {"ic_launcher_foreground": fg, "ic_launcher_monochrome": mono, "ic_notification": notif}, adaptive


def main(res):
    res = Path(res)
    (res / "drawable").mkdir(parents=True, exist_ok=True)
    (res / "mipmap-anydpi").mkdir(parents=True, exist_ok=True)
    drawables = {**maneuvers(), **actions()}
    lau, adaptive = launcher()
    drawables.update(lau)
    for name, xml in drawables.items():
        (res / "drawable" / f"{name}.xml").write_text(xml, encoding="utf-8")
    for name in ("ic_launcher", "ic_launcher_round"):
        (res / "mipmap-anydpi" / f"{name}.xml").write_text(adaptive, encoding="utf-8")
    print(f"{len(drawables)} drawables, 2 adaptive launcher icons -> {res}")


if __name__ == "__main__":
    main(sys.argv[1])
