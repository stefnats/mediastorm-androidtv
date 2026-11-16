package com.strmr.mobile.downloads

import com.strmr.tv.api.ApiJson
import java.io.File
import kotlinx.serialization.json.*

/** Whole-store persistence keeps inaccessible records and opaque future rows without assigning ownership. */
internal class DownloadRepository(private val file:File) {
    private var revision=Long.MIN_VALUE
    private var readable=false
    private var opaque=emptyList<JsonElement>()
    private var originals=emptyMap<String,Pair<DownloadItem,JsonObject>>()
    private val fields=DownloadItem.serializer().descriptor.let {descriptor->(0 until descriptor.elementsCount).map {descriptor.getElementName(it)}.toSet()}
    @Synchronized fun load():List<DownloadItem> {
        readable=false
        if(!file.isFile){readable=true;return emptyList()}
        val raw=runCatching {ApiJson.parseToJsonElement(file.readText()) as? JsonArray}.getOrNull()
            ?: throw DownloadAccessError("Saved downloads could not be read. Their files were kept.")
        // A corrupt/hand-edited ledger must never assign the same physical bytes to two owners.
        val ids=raw.mapNotNull {(it as? JsonObject)?.get("id")?.let {value->(value as? JsonPrimitive)?.takeIf {it.isString}?.content}}
        val names=raw.mapNotNull {(it as? JsonObject)?.get("fileName")?.let {value->(value as? JsonPrimitive)?.takeIf {it.isString}?.content}}
        val duplicateIds=ids.groupingBy {it}.eachCount().filterValues {it>1}.keys
        val duplicateNames=names.groupingBy {it}.eachCount().filterValues {it>1}.keys
        val unknown=mutableListOf<JsonElement>()
        val items=raw.mapNotNull {row->
            val fields=row as? JsonObject
            val status=fields?.get("status") as? JsonPrimitive
            val collision=(fields?.get("id") as? JsonPrimitive)?.contentOrNull in duplicateIds || (fields?.get("fileName") as? JsonPrimitive)?.contentOrNull in duplicateNames
            val supported=!collision && (status==null || status.isString && status.content in DownloadItem.Status.entries.map {it.name})
            val item=if(supported)runCatching {ApiJson.decodeFromJsonElement<DownloadItem>(row)}.getOrNull()else null
            if(item==null)unknown+=row
            item
        }
        originals=items.associate {item->item.id to (item to (raw.first {row->(row as? JsonObject)?.get("id")==JsonPrimitive(item.id)} as JsonObject))}
        opaque=unknown;readable=true
        return items
    }
    @Synchronized fun save(version:Long,items:List<DownloadItem>):Boolean {
        if(!readable || version<revision)return false
        file.parentFile?.mkdirs()
        val raw=JsonArray(items.map {item->
            val old=originals[item.id]
            if(old?.first==item)old.second else {
                val encoded=ApiJson.encodeToJsonElement(item) as JsonObject
                JsonObject(old?.second.orEmpty().filterKeys {it !in fields}+encoded)
            }
        }+opaque)
        val temporary=File(file.parentFile,file.name+".tmp")
        temporary.writeText(raw.toString())
        check(temporary.renameTo(file)){"Saved downloads could not be updated."}
        revision=version
        return true
    }
}
