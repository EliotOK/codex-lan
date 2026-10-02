package local.codex.lan

import org.json.JSONObject
import org.json.JSONArray

data class UploadedFile(val id: String, val name: String, val size: Long, val image: Boolean) {
    fun json() = JSONObject().put("id",id).put("name",name).put("size",size).put("image",image)
    fun preview(thread: String) = if(image)ChatImage("/api/upload-images/$thread/$id",name) else null
    companion object {
        fun parse(value: JSONObject): UploadedFile {
            val id=value.getString("id");val name=value.getString("name");val size=value.getLong("size")
            require(id.matches(Regex("[a-fA-F0-9]{8}-(?:[a-fA-F0-9]{4}-){3}[a-fA-F0-9]{12}")) && name.isNotBlank() && name.length<=120 && size in 1..LanClient.MAX_IMAGE_BYTES.toLong()) { "附件信息无效" }
            return UploadedFile(id,name,size,value.optBoolean("image"))
        }
        fun list(value: JSONArray?) = (0 until (value?.length() ?: 0).coerceAtMost(5)).map { parse(value!!.getJSONObject(it)) }
        fun json(files: List<UploadedFile>) = JSONArray(files.map { it.json() })
    }
}
