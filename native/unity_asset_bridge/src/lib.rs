use std::any::Any;
use std::collections::HashSet;
use std::fs::File;
use std::io::{BufRead, BufReader, BufWriter, Write};
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::path::Path;

use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jlong, jstring};
use serde_json::{Value, json};
use unity_rs_core::export::{
    AudioExportFormat, ExportOptions, ExportPlan, ExportReport, FilenameFormat,
    export_collection_with_plan,
};
use unity_rs_core::image_export::PngCompression;
use unity_rs_core::loader::{AssetLoadOptions, LoadFailurePolicy};
use unity_rs_core::studio::Studio;

const TEXTURE_2D_CLASS_ID: i32 = 28;
const TEXT_ASSET_CLASS_ID: i32 = 49;
const AUDIO_CLIP_CLASS_ID: i32 = 83;
const FONT_CLASS_ID: i32 = 128;
const MOVIE_TEXTURE_CLASS_ID: i32 = 152;
const TEXTURE_2D_ARRAY_CLASS_ID: i32 = 187;
const SPRITE_CLASS_ID: i32 = 213;
const VIDEO_CLIP_CLASS_ID: i32 = 329;
const MESH_CLASS_ID: i32 = 43;
const SHADER_CLASS_ID: i32 = 48;
const MAX_FAILURE_MESSAGES: usize = 80;
const MAX_MANIFEST_RECORDS: usize = 50_000;
const MAX_MANIFEST_DIAGNOSTICS: usize = 2_000;
const MAXIMUM_SINGLE_OBJECT_BYTES: u64 = 1024 * 1024 * 1024;
const MAX_EXISTING_OUTPUT_INDEX_BYTES: u64 = 64 * 1024 * 1024;
const MAX_EXISTING_OUTPUTS: usize = 1_000_000;
const MAX_PORTABLE_EXPORT_COMPONENT_BYTES: usize = 240;

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_pockettoolbox_app_data_UnityNativeAssetBridge_nativeExportMedia(
    mut env: JNIEnv,
    _class: JClass,
    input_path: JString,
    output_path: JString,
    existing_output_index_path: JString,
    export_images: jboolean,
    export_audio: jboolean,
    export_video: jboolean,
    export_text: jboolean,
    maximum_output_bytes: jlong,
) -> jstring {
    let payload = match catch_unwind(AssertUnwindSafe(|| {
        export_from_jni(
            &mut env,
            input_path,
            output_path,
            existing_output_index_path,
            export_images != 0,
            export_audio != 0,
            export_video != 0,
            export_text != 0,
            maximum_output_bytes,
        )
    })) {
        Ok(Ok(value)) => value,
        Ok(Err(error)) => json!({ "ok": false, "error": error }),
        Err(payload) => json!({
            "ok": false,
            "error": format!("Unity 原生解析发生异常：{}", panic_message(payload.as_ref())),
        }),
    };

    match env.new_string(payload.to_string()) {
        Ok(value) => value.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

fn export_from_jni(
    env: &mut JNIEnv,
    input_path: JString,
    output_path: JString,
    existing_output_index_path: JString,
    export_images: bool,
    export_audio: bool,
    export_video: bool,
    export_text: bool,
    maximum_output_bytes: jlong,
) -> Result<Value, String> {
    if !export_images && !export_audio && !export_video && !export_text {
        return Err("至少需要选择图片、音频、视频或文本对象".to_string());
    }
    let maximum_output_bytes =
        u64::try_from(maximum_output_bytes).map_err(|_| "输出上限必须为正数".to_string())?;
    if maximum_output_bytes == 0 {
        return Err("输出上限必须大于 0".to_string());
    }

    let input_path: String = env
        .get_string(&input_path)
        .map_err(|error| format!("无法读取输入路径：{error}"))?
        .into();
    let output_path: String = env
        .get_string(&output_path)
        .map_err(|error| format!("无法读取输出路径：{error}"))?
        .into();
    let existing_output_index_path: String = env
        .get_string(&existing_output_index_path)
        .map_err(|error| format!("无法读取已有产物索引路径：{error}"))?
        .into();

    let studio = Studio::open_with_options(
        Path::new(&input_path),
        AssetLoadOptions {
            failure_policy: LoadFailurePolicy::SkipInput,
            ..AssetLoadOptions::default()
        },
    )
    .map_err(|error| format!("无法载入 Unity 资源：{error}"))?;
    let serialized_file_count = studio.file_count();
    let object_count = studio.object_count();
    let resource_count = studio.resource_count();
    let diagnostic_count = studio.load_diagnostics().len();
    let diagnostic_messages = studio
        .load_diagnostics()
        .iter()
        .map(|diagnostic| format!("{}（输入已跳过）：{}", diagnostic.path, diagnostic.message))
        .collect::<Vec<_>>();
    let mut classes = Vec::with_capacity(7);
    if export_images {
        classes.extend([
            TEXTURE_2D_CLASS_ID,
            TEXTURE_2D_ARRAY_CLASS_ID,
            SPRITE_CLASS_ID,
        ]);
    }
    if export_audio {
        classes.push(AUDIO_CLIP_CLASS_ID);
    }
    if export_video {
        classes.extend([MOVIE_TEXTURE_CLASS_ID, VIDEO_CLIP_CLASS_ID]);
    }
    if export_text {
        classes.push(TEXT_ASSET_CLASS_ID);
    }
    let existing_outputs = read_existing_output_index(&existing_output_index_path);
    let existing_skipped = remove_fully_exported_classes(&studio, &mut classes, &existing_outputs);
    let collection = studio.into_collection();

    let single_object_limit = maximum_output_bytes.min(MAXIMUM_SINGLE_OBJECT_BYTES);
    let maximum_text_asset_bytes = usize::try_from(single_object_limit).unwrap_or(usize::MAX);
    let options = ExportOptions {
        filename_format: FilenameFormat::AssetNamePathId,
        audio_format: AudioExportFormat::Auto,
        png_compression: PngCompression::Fast,
        overwrite_existing: true,
        maximum_total_output_bytes: maximum_output_bytes,
        maximum_raw_object_bytes: single_object_limit,
        maximum_text_asset_bytes,
        maximum_simple_asset_bytes: single_object_limit,
        maximum_audio_output_bytes: single_object_limit,
        maximum_texture_output_bytes: single_object_limit,
        maximum_texture_array_output_bytes: single_object_limit,
        maximum_texture_array_bundle_bytes: maximum_output_bytes,
        maximum_sprite_output_bytes: single_object_limit,
        ..ExportOptions::default()
    };
    let mut report = export_collection_with_plan(
        &collection,
        Path::new(&output_path),
        options,
        ExportPlan {
            mono_schemas: None,
            classes: Some(&classes),
        },
    )
    .map_err(|error| format!("Unity 对象导出失败：{error}"))?;
    let organization_failures = organize_export_outputs(&mut report);

    let image_count = report
        .exported
        .iter()
        .filter(|record| {
            matches!(
                record.class_id,
                TEXTURE_2D_CLASS_ID | TEXTURE_2D_ARRAY_CLASS_ID | SPRITE_CLASS_ID
            )
        })
        .count();
    let audio_count = report
        .exported
        .iter()
        .filter(|record| record.class_id == AUDIO_CLIP_CLASS_ID)
        .count();
    let video_count = report
        .exported
        .iter()
        .filter(|record| {
            matches!(
                record.class_id,
                MOVIE_TEXTURE_CLASS_ID | VIDEO_CLIP_CLASS_ID
            )
        })
        .count();
    let text_count = report
        .exported
        .iter()
        .filter(|record| record.class_id == TEXT_ASSET_CLASS_ID)
        .count();
    let manifest_error = write_export_manifest(
        Path::new(&output_path),
        serialized_file_count,
        object_count,
        resource_count,
        &diagnostic_messages,
        &report,
    )
    .err();

    let mut failure_messages = Vec::with_capacity(MAX_FAILURE_MESSAGES);
    if let Some(error) = manifest_error.as_ref() {
        failure_messages.push(format!("导出清单写入失败：{error}"));
    }
    for message in organization_failures {
        if failure_messages.len() >= MAX_FAILURE_MESSAGES {
            break;
        }
        failure_messages.push(message);
    }
    for message in diagnostic_messages.iter().cloned() {
        if failure_messages.len() >= MAX_FAILURE_MESSAGES {
            break;
        }
        failure_messages.push(message);
    }
    for failure in report.failures.iter().chain(report.unsupported.iter()) {
        if failure_messages.len() >= MAX_FAILURE_MESSAGES {
            break;
        }
        failure_messages.push(format!(
            "{}::{}（class {}）：{}",
            failure.source, failure.path_id, failure.class_id, failure.error
        ));
    }

    Ok(json!({
        "ok": true,
        "exported": report.exported.len(),
        "images": image_count,
        "audio": audio_count,
        "video": video_count,
        "text": text_count,
        "existingSkipped": existing_skipped,
        "failed": report.failures.len(),
        "unsupported": report.unsupported.len(),
        "serializedFiles": serialized_file_count,
        "objects": object_count,
        "resources": resource_count,
        "diagnostics": diagnostic_count,
        "manifestWritten": manifest_error.is_none(),
        "failureMessages": failure_messages,
    }))
}

fn read_existing_output_index(path: &str) -> HashSet<String> {
    if path.is_empty() {
        return HashSet::new();
    }
    let Ok(file) = File::open(path) else {
        return HashSet::new();
    };
    if file.metadata().map_or(true, |metadata| {
        metadata.len() > MAX_EXISTING_OUTPUT_INDEX_BYTES
    }) {
        return HashSet::new();
    }
    BufReader::new(file)
        .lines()
        .take(MAX_EXISTING_OUTPUTS)
        .filter_map(Result::ok)
        .filter_map(|line| normalize_existing_output_key(&line))
        .collect()
}

fn normalize_existing_output_key(line: &str) -> Option<String> {
    if line.len() > 1024 {
        return None;
    }
    let mut parts = line.split('|');
    let group = parts.next()?.trim();
    let category = parts.next()?.trim();
    let path_id = parts.next()?.trim().parse::<i64>().ok()?;
    if parts.next().is_some()
        || group.is_empty()
        || !matches!(category, "picture" | "audio" | "video" | "text")
    {
        return None;
    }
    Some(existing_output_key(
        &group.to_lowercase(),
        category,
        path_id,
    ))
}

fn remove_fully_exported_classes(
    studio: &Studio,
    classes: &mut Vec<i32>,
    existing_outputs: &HashSet<String>,
) -> usize {
    if existing_outputs.is_empty() {
        return 0;
    }
    let mut skipped = 0_usize;
    classes.retain(|class_id| {
        let Some(category) = export_category(*class_id) else {
            return true;
        };
        let mut object_count = 0_usize;
        let mut all_exist = true;
        for object in studio
            .objects()
            .filter(|object| object.class_id() == *class_id)
        {
            object_count = object_count.saturating_add(1);
            let Some(group_name) = export_group_name(object.file_index(), object.source_path())
            else {
                all_exist = false;
                break;
            };
            if !existing_outputs.contains(&existing_output_key(
                &group_name,
                category,
                object.path_id(),
            )) {
                all_exist = false;
                break;
            }
        }
        if object_count > 0 && all_exist {
            skipped = skipped.saturating_add(object_count);
            false
        } else {
            true
        }
    });
    skipped
}

fn export_group_name(file_index: usize, source_path: &str) -> Option<String> {
    let prefix = format!("{file_index:04}_");
    let name_limit = MAX_PORTABLE_EXPORT_COMPONENT_BYTES.checked_sub(prefix.len())?;
    let source_name = source_path
        .rsplit(['/', '\\', ':'])
        .find(|part| !part.is_empty())
        .unwrap_or(source_path);
    let source_name = sanitize_export_component(source_name, name_limit)?;
    Some(format!("{prefix}{source_name}").to_lowercase())
}

fn sanitize_export_component(value: &str, maximum_bytes: usize) -> Option<String> {
    let value = value.trim_matches([' ', '.']);
    if value.is_empty() {
        return Some("unnamed".to_string());
    }
    let mut output = String::new();
    for character in value.chars() {
        let character = if character.is_control()
            || matches!(
                character,
                '/' | '\\' | ':' | '*' | '?' | '"' | '<' | '>' | '|'
            ) {
            '_'
        } else {
            character
        };
        if output.len().checked_add(character.len_utf8())? > maximum_bytes {
            return None;
        }
        output.push(character);
    }
    Some(output)
}

fn existing_output_key(group: &str, category: &str, path_id: i64) -> String {
    format!("{group}|{category}|{path_id}")
}

fn organize_export_outputs(report: &mut ExportReport) -> Vec<String> {
    let mut failures = Vec::new();
    for record in &mut report.exported {
        let Some(category) = export_category(record.class_id) else {
            continue;
        };
        let Some(parent) = record.output_path.parent() else {
            continue;
        };
        let Some(file_name) = record.output_path.file_name() else {
            continue;
        };
        let category_directory = parent.join(category);
        let target = category_directory.join(file_name);
        let result = std::fs::create_dir_all(&category_directory)
            .map_err(|error| format!("无法创建分类目录 {}：{error}", category_directory.display()))
            .and_then(|_| {
                if target.exists() {
                    return Err(format!("分类目标已存在：{}", target.display()));
                }
                std::fs::rename(&record.output_path, &target).map_err(|error| {
                    format!(
                        "无法将 {} 移入 {}：{error}",
                        record.output_path.display(),
                        category_directory.display()
                    )
                })
            });
        match result {
            Ok(()) => record.output_path = target,
            Err(error) if failures.len() < MAX_FAILURE_MESSAGES => failures.push(error),
            Err(_) => {}
        }
    }
    failures
}

const fn export_category(class_id: i32) -> Option<&'static str> {
    match class_id {
        TEXTURE_2D_CLASS_ID | TEXTURE_2D_ARRAY_CLASS_ID | SPRITE_CLASS_ID => Some("picture"),
        AUDIO_CLIP_CLASS_ID => Some("audio"),
        MOVIE_TEXTURE_CLASS_ID | VIDEO_CLIP_CLASS_ID => Some("video"),
        TEXT_ASSET_CLASS_ID => Some("text"),
        FONT_CLASS_ID => Some("font"),
        MESH_CLASS_ID => Some("model"),
        SHADER_CLASS_ID => Some("shader"),
        _ => None,
    }
}

fn write_export_manifest(
    output_root: &Path,
    serialized_file_count: usize,
    object_count: usize,
    resource_count: usize,
    diagnostics: &[String],
    report: &ExportReport,
) -> Result<(), String> {
    let manifest_path = output_root.join("unity_export_manifest.json");
    let temporary_path = output_root.join("unity_export_manifest.json.partial");
    let result = write_export_manifest_file(
        &temporary_path,
        output_root,
        serialized_file_count,
        object_count,
        resource_count,
        diagnostics,
        report,
    )
    .and_then(|_| {
        std::fs::rename(&temporary_path, &manifest_path)
            .map_err(|error| format!("无法发布 {}：{error}", manifest_path.display()))
    });
    if result.is_err() {
        let _ = std::fs::remove_file(&temporary_path);
    }
    result
}

fn write_export_manifest_file(
    manifest_path: &Path,
    output_root: &Path,
    serialized_file_count: usize,
    object_count: usize,
    resource_count: usize,
    diagnostics: &[String],
    report: &ExportReport,
) -> Result<(), String> {
    let file = File::create(manifest_path)
        .map_err(|error| format!("无法创建 {}：{error}", manifest_path.display()))?;
    let mut writer = BufWriter::new(file);
    writer
        .write_all(b"{\"schemaVersion\":1,\"summary\":")
        .map_err(|error| format!("写入清单失败：{error}"))?;
    serde_json::to_writer(
        &mut writer,
        &json!({
            "serializedFiles": serialized_file_count,
            "objects": object_count,
            "resources": resource_count,
            "exported": report.exported.len(),
            "failed": report.failures.len(),
            "unsupported": report.unsupported.len(),
            "exportRecordsTruncated": report.exported.len() > MAX_MANIFEST_RECORDS,
            "diagnosticsTruncated": diagnostics.len() > MAX_MANIFEST_DIAGNOSTICS,
            "failureRecordsTruncated": report.failures.len().saturating_add(report.unsupported.len())
                > MAX_MANIFEST_DIAGNOSTICS,
        }),
    )
    .map_err(|error| format!("序列化清单摘要失败：{error}"))?;

    writer
        .write_all(b",\"exports\":[")
        .map_err(|error| format!("写入清单失败：{error}"))?;
    for (index, record) in report
        .exported
        .iter()
        .take(MAX_MANIFEST_RECORDS)
        .enumerate()
    {
        if index > 0 {
            writer
                .write_all(b",")
                .map_err(|error| format!("写入清单失败：{error}"))?;
        }
        let relative_output = record
            .output_path
            .strip_prefix(output_root)
            .unwrap_or(record.output_path.as_path())
            .to_string_lossy()
            .replace('\\', "/");
        let output_bytes = record
            .output_path
            .metadata()
            .ok()
            .map(|metadata| metadata.len());
        serde_json::to_writer(
            &mut writer,
            &json!({
                "source": record.source,
                "pathId": record.path_id,
                "classId": record.class_id,
                "payloadKind": record.payload_kind,
                "output": relative_output,
                "bytes": output_bytes,
            }),
        )
        .map_err(|error| format!("序列化导出记录失败：{error}"))?;
    }

    writer
        .write_all(b"],\"loadDiagnostics\":[")
        .map_err(|error| format!("写入清单失败：{error}"))?;
    for (index, diagnostic) in diagnostics
        .iter()
        .take(MAX_MANIFEST_DIAGNOSTICS)
        .enumerate()
    {
        if index > 0 {
            writer
                .write_all(b",")
                .map_err(|error| format!("写入清单失败：{error}"))?;
        }
        serde_json::to_writer(&mut writer, diagnostic)
            .map_err(|error| format!("序列化载入诊断失败：{error}"))?;
    }

    writer
        .write_all(b"],\"exportFailures\":[")
        .map_err(|error| format!("写入清单失败：{error}"))?;
    for (index, (category, failure)) in report
        .failures
        .iter()
        .map(|failure| ("failed", failure))
        .chain(
            report
                .unsupported
                .iter()
                .map(|failure| ("unsupported", failure)),
        )
        .take(MAX_MANIFEST_DIAGNOSTICS)
        .enumerate()
    {
        if index > 0 {
            writer
                .write_all(b",")
                .map_err(|error| format!("写入清单失败：{error}"))?;
        }
        serde_json::to_writer(
            &mut writer,
            &json!({
                "category": category,
                "source": failure.source,
                "pathId": failure.path_id,
                "classId": failure.class_id,
                "error": failure.error,
            }),
        )
        .map_err(|error| format!("序列化失败记录失败：{error}"))?;
    }
    writer
        .write_all(b"]}")
        .and_then(|_| writer.flush())
        .map_err(|error| format!("完成清单写入失败：{error}"))
}

fn panic_message(payload: &(dyn Any + Send)) -> &str {
    payload
        .downcast_ref::<&str>()
        .copied()
        .or_else(|| payload.downcast_ref::<String>().map(String::as_str))
        .unwrap_or("未知 panic")
}
