//! USB Audio Class capture-gain control via JNI to UsbManager.
//!
//! AAudio on Android does not expose any equivalent of ALSA's
//! `amixer set Capture XdB`. The Linux graywolf-modem operator workflow
//! for a Digirig + UV5R chain calibrates the codec's capture-side gain
//! to roughly -35 dB; without that stage the analog-into-digital
//! conversion saturates the i16 range on normal APRS-volume audio.
//!
//! This module reaches around AAudio by talking directly to the USB
//! Audio Class control interface via Android's UsbManager:
//!
//!   1. Enumerate USB devices via `UsbManager.getDeviceList()`.
//!   2. Pick one that exposes a USB Audio Class (0x01) interface.
//!   3. Open a `UsbDeviceConnection` and walk the Audio Control
//!      interface descriptor to find the input Feature Unit and its
//!      Volume Control selector.
//!   4. Issue a USB Audio Class SET_CUR control transfer with the
//!      desired dB target, mirroring what `snd-usb-audio` does on
//!      Linux when the operator runs `amixer set Capture -35dB`.
//!
//! Stage 1 (this commit): just enumerate + log. Stages 2-4 follow
//! once we know what the device descriptors look like.

use android_activity::AndroidApp;
use jni::objects::{JObject, JString};
use jni::JavaVM;
use log::{info, warn};

/// Best-effort Stage-1 implementation: list every USB device and its
/// interface table, log the result, return Ok regardless. No control
/// transfers issued yet.
pub fn enumerate_and_set_volume(app: &AndroidApp, target_db: f32) -> Result<(), String> {
    let vm_ptr = app.vm_as_ptr() as *mut jni::sys::JavaVM;
    let activity_ptr = app.activity_as_ptr() as jni::sys::jobject;
    if vm_ptr.is_null() || activity_ptr.is_null() {
        return Err("AndroidApp has null VM or Activity pointer".into());
    }

    let vm = unsafe { JavaVM::from_raw(vm_ptr) }.map_err(|e| format!("JavaVM::from_raw: {}", e))?;
    let mut env = vm
        .attach_current_thread()
        .map_err(|e| format!("attach_current_thread: {}", e))?;
    let context = unsafe { JObject::from_raw(activity_ptr) };

    // UsbManager um = (UsbManager) context.getSystemService("usb");
    let svc_name = env
        .new_string("usb")
        .map_err(|e| format!("new_string usb: {}", e))?;
    let usb_manager = env
        .call_method(
            &context,
            "getSystemService",
            "(Ljava/lang/String;)Ljava/lang/Object;",
            &[(&svc_name).into()],
        )
        .and_then(|v| v.l())
        .map_err(|e| format!("getSystemService(usb): {}", e))?;

    // HashMap<String, UsbDevice> map = um.getDeviceList();
    let device_map = env
        .call_method(&usb_manager, "getDeviceList", "()Ljava/util/HashMap;", &[])
        .and_then(|v| v.l())
        .map_err(|e| format!("getDeviceList: {}", e))?;
    let values = env
        .call_method(&device_map, "values", "()Ljava/util/Collection;", &[])
        .and_then(|v| v.l())
        .map_err(|e| format!("values: {}", e))?;
    let iter = env
        .call_method(&values, "iterator", "()Ljava/util/Iterator;", &[])
        .and_then(|v| v.l())
        .map_err(|e| format!("iterator: {}", e))?;

    let mut count = 0u32;
    let mut audio_count = 0u32;
    loop {
        let has_next = env
            .call_method(&iter, "hasNext", "()Z", &[])
            .and_then(|v| v.z())
            .map_err(|e| format!("hasNext: {}", e))?;
        if !has_next {
            break;
        }
        let device = env
            .call_method(&iter, "next", "()Ljava/lang/Object;", &[])
            .and_then(|v| v.l())
            .map_err(|e| format!("next: {}", e))?;

        let vid = env
            .call_method(&device, "getVendorId", "()I", &[])
            .and_then(|v| v.i())
            .unwrap_or(-1);
        let pid = env
            .call_method(&device, "getProductId", "()I", &[])
            .and_then(|v| v.i())
            .unwrap_or(-1);
        let class = env
            .call_method(&device, "getDeviceClass", "()I", &[])
            .and_then(|v| v.i())
            .unwrap_or(-1);

        let name = env
            .call_method(&device, "getDeviceName", "()Ljava/lang/String;", &[])
            .and_then(|v| v.l())
            .map(|o| {
                env.get_string(&JString::from(o))
                    .map(|s| s.to_string_lossy().into_owned())
                    .unwrap_or_default()
            })
            .unwrap_or_default();

        let prod_name = env
            .call_method(&device, "getProductName", "()Ljava/lang/String;", &[])
            .and_then(|v| v.l())
            .map(|o| {
                if o.is_null() {
                    String::new()
                } else {
                    env.get_string(&JString::from(o))
                        .map(|s| s.to_string_lossy().into_owned())
                        .unwrap_or_default()
                }
            })
            .unwrap_or_default();

        info!(
            "USB device #{}: name={} product='{}' vid=0x{:04x} pid=0x{:04x} devClass=0x{:02x}",
            count, name, prod_name, vid, pid, class
        );

        let iface_count = env
            .call_method(&device, "getInterfaceCount", "()I", &[])
            .and_then(|v| v.i())
            .unwrap_or(0);
        let mut has_audio = false;
        for i in 0..iface_count {
            let iface = match env
                .call_method(
                    &device,
                    "getInterface",
                    "(I)Landroid/hardware/usb/UsbInterface;",
                    &[(i as jni::sys::jint).into()],
                )
                .and_then(|v| v.l())
            {
                Ok(o) => o,
                Err(e) => {
                    warn!("  iface[{}] read err: {}", i, e);
                    continue;
                }
            };
            let iclass = env
                .call_method(&iface, "getInterfaceClass", "()I", &[])
                .and_then(|v| v.i())
                .unwrap_or(-1);
            let isub = env
                .call_method(&iface, "getInterfaceSubclass", "()I", &[])
                .and_then(|v| v.i())
                .unwrap_or(-1);
            let iproto = env
                .call_method(&iface, "getInterfaceProtocol", "()I", &[])
                .and_then(|v| v.i())
                .unwrap_or(-1);
            info!(
                "  iface[{}] class=0x{:02x} sub=0x{:02x} proto=0x{:02x}",
                i, iclass, isub, iproto
            );
            if iclass == 0x01 {
                has_audio = true;
            }
        }
        if has_audio {
            audio_count += 1;
            // Probe permission status for Stage 2 design.
            let has_perm = env
                .call_method(
                    &usb_manager,
                    "hasPermission",
                    "(Landroid/hardware/usb/UsbDevice;)Z",
                    &[(&device).into()],
                )
                .and_then(|v| v.z())
                .unwrap_or(false);
            info!(
                "  -> USB Audio Class device, hasPermission={} (target gain {} dB)",
                has_perm, target_db
            );
        }
        count += 1;
    }
    info!(
        "USB enumeration: {} device(s), {} with USB Audio class interface",
        count, audio_count
    );
    Ok(())
}
