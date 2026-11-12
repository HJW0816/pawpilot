package com.estareng.pawpilot.navigation

import android.content.Context
import org.json.JSONObject

data class PetRestStopCatalog(val source: String, val asOf: String, val stops: List<PetRestStop>) {
    companion object {
        fun load(context: Context): PetRestStopCatalog {
            val data = context.assets.open("pet_rest_facilities.json").bufferedReader().use { JSONObject(it.readText()) }
            val rows = data.getJSONArray("facilities")
            val stops = groupRestStops((0 until rows.length()).map { index ->
                val row = rows.getJSONObject(index)
                row.getString("name") to PetFacility(row.getString("kind"), row.getString("location"),
                    row.getString("hours"), row.getString("closed"))
            })
            return PetRestStopCatalog(data.getString("source"), data.getString("asOf"), stops)
        }
    }
}
