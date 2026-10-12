package com.example.gymbro.session

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.example.gymbro.R

// Grid of statistics (label + value, e.g. AVG SPEED 10.2 km/h), in rows of [columns] tiles
// (res/layout/item_stat.xml). The tiles are created once, then only their texts change: during a
// run they are updated every second.
class StatGrid(private val container: LinearLayout, private val columns: Int) {

    private val tiles = mutableListOf<Pair<TextView, TextView>>()

    fun show(stats: List<Pair<String, String>>) {
        if (stats.size != tiles.size) createTiles(stats.size)
        stats.forEachIndexed { i, (label, value) ->
            tiles[i].first.text = label
            tiles[i].second.text = value
        }
    }

    private fun createTiles(count: Int) {
        container.removeAllViews()
        tiles.clear()
        val context = container.context
        val inflater = LayoutInflater.from(context)

        for (rowStart in 0 until count step columns) {
            val row = LinearLayout(context)
            row.orientation = LinearLayout.HORIZONTAL
            container.addView(row, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

            for (i in rowStart until rowStart + columns) {
                if (i < count) {
                    val tile = inflater.inflate(R.layout.item_stat, row, false)
                    row.addView(tile)
                    tiles.add(tile.findViewById<TextView>(R.id.statLabel) to tile.findViewById(R.id.statValue))
                } else {
                    // empty space: the tiles of an incomplete last row keep the same width
                    val margin = (4 * context.resources.displayMetrics.density).toInt()
                    row.addView(View(context), LinearLayout.LayoutParams(0, 0, 1f).apply { setMargins(margin, 0, margin, 0) })
                }
            }
        }
    }
}
