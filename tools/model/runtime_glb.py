"""Runtime variant of the design's drone model (D25 still applies at run time):

    blender -b --factory-startup -P tools/model/runtime_glb.py -- IN.glb OUT.glb

The design GLB (apps/drone-sight/dronemodel/renders/quad_A.glb, 29 nodes, emissive strength 6 on the LEDs) is joined
per material into ~10 objects (named after the material: armFA, armRA, battA, bodyA, motor, ledF, ledR, skidA, propA,
topA), the LED emissive strength is set to 1 and every material stays OPAQUE. Geometry, size, origin and the nose (-Z
in glTF) are unchanged: the app measures the box and the front (ledF) / rear (ledR) LEDs when it loads the file.
"""
import sys

import bpy

argv = sys.argv[sys.argv.index("--") + 1:]
src, dst = argv[0], argv[1]
bpy.ops.wm.read_factory_settings(use_empty=True)
bpy.ops.import_scene.gltf(filepath=src)
meshes = [o for o in bpy.context.scene.objects if o.type == "MESH"]
# apply parent transforms so the join keeps world positions
for o in meshes:
    o.select_set(True)
bpy.context.view_layer.objects.active = meshes[0]
bpy.ops.object.parent_clear(type="CLEAR_KEEP_TRANSFORM")
bpy.ops.object.transform_apply(location=True, rotation=True, scale=True)
by_mat = {}
for o in meshes:
    m = o.active_material.name if o.active_material else "none"
    by_mat.setdefault(m, []).append(o)
for name, objs in by_mat.items():
    bpy.ops.object.select_all(action="DESELECT")
    for o in objs:
        o.select_set(True)
    bpy.context.view_layer.objects.active = objs[0]
    if len(objs) > 1:
        bpy.ops.object.join()
    obj = bpy.context.view_layer.objects.active
    obj.name = name
    obj.data.name = name
for m in bpy.data.materials:
    if m.use_nodes:
        for n in m.node_tree.nodes:
            if n.type == "BSDF_PRINCIPLED" and "Emission Strength" in n.inputs:
                if n.inputs["Emission Strength"].default_value > 1.0:
                    n.inputs["Emission Strength"].default_value = 1.0
    m.blend_method = "OPAQUE" if hasattr(m, "blend_method") else None
# remove empties
for o in list(bpy.context.scene.objects):
    if o.type == "EMPTY":
        bpy.data.objects.remove(o, do_unlink=True)
bpy.ops.export_scene.gltf(filepath=dst, export_format="GLB", export_yup=True, export_apply=True)
print("objects:", sorted(o.name for o in bpy.context.scene.objects))
