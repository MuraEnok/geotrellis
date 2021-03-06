package geotrellis.raster

import geotrellis._
import geotrellis.util.Filesystem
import geotrellis.process._
import geotrellis.data.arg.{ArgWriter,ArgReader}
import geotrellis.data.Gdal
import geotrellis.feature.Polygon
import java.io.{FileOutputStream, BufferedOutputStream}
import geotrellis.util.Filesystem

/**
 * Used to create tiled rasters, as well as tilesets on the filesystem, based
 * on a source raster.
 *
 * These files (on disk) can be used by a TileSetRasterData, or loaded into an
 * array of rasters to be used by TileArrayRasterData.
 *
 * A tile set has a base path (e.g. "foo/bar") which is used along with the
 * "tile coordinates" (e.g. tile 0,4) to compute the path of each tile (in this
 * case "foo/bar_0_4.arg").
 */
object Tiler {
  /**
   * Given a name ("bar") a col (0), and a row (4), returns the correct name
   * for this tile ("bar_0_4").
   */
  def tileName(name:String, col:Int, row:Int) = {
    "%s_%d_%d".format(name, col, row)
  }

  /**
   * Given a path ("foo"), a name ("bar"), a col (0), and a row (4), returns
   * the correct name for this tile ("foo/bar_0_4").
   */
  def tilePath(path:String, name:String, col:Int, row:Int) = {
    Filesystem.join(path, tileName(name, col, row) + ".arg")
  }

  /**
   * From a raster, makes a new Raster (using an array of tiles in memory).
   */
  def createTiledRaster(src:Raster, pixelCols:Int, pixelRows:Int) = {
    val data = createTiledRasterData(src, pixelCols, pixelRows)
    Raster(data, src.rasterExtent)
  }
 
  def buildTileLayout(re:RasterExtent, pixelCols:Int, pixelRows:Int) = {
    val tileCols = (re.cols + pixelCols - 1) / pixelCols
    val tileRows = (re.rows + pixelRows - 1) / pixelRows
    TileLayout(tileCols, tileRows, pixelCols, pixelRows)
  }

  def buildTileRasterExtent(tx:Int, ty:Int, re:RasterExtent, pixelCols:Int, pixelRows:Int) = {
    val cw = re.cellwidth
    val ch = re.cellheight
    val e = re.extent
    // Note that since tile (0,0) is in the upper-left corner of our map,
    // we need to fix xmin and ymax to e.xmin and e.ymax. This asymmetry
    // will seem strange until you consider that fact.
    val xmin = e.xmin + (cw * tx * pixelCols)
    val ymax = e.ymax - (ch * ty * pixelRows)
    val xmax = xmin + (cw * pixelCols)
    val ymin = ymax - (cw * pixelRows)

    val te = Extent(xmin, ymin, xmax, ymax)
    val tre = RasterExtent(te, cw, ch, pixelCols, pixelRows)
    tre
  }

  /**
   * From a raster, makes a new TiledArrayRaster (an array of tiles in memory).
   */
  def createTiledRasterData(src:Raster, pixelCols:Int, pixelRows:Int) = {
    val re = src.rasterExtent
    val e = re.extent
  
    val layout = buildTileLayout(re, pixelCols, pixelRows)
    val tileCols = layout.tileCols
    val tileRows = layout.tileRows

    val cw = re.cellwidth
    val ch = re.cellheight

    val rasters = Array.ofDim[Raster](tileCols * tileRows)

    for (ty <- 0 until tileRows; tx <- 0 until tileCols) yield {
      val data = RasterData.allocByType(src.data.getType, pixelCols, pixelRows)

      // TODO: if this code ends up being a performance bottleneck, we should
      // refactor away from using raster.get and for-comprehensions.
      for (y <- 0 until pixelRows; x <- 0 until pixelCols) {
        val xsrc = tx * pixelCols + x
        val ysrc = ty * pixelRows + y
        val i = y * pixelCols + x
        data(i) = if (xsrc >= re.cols || ysrc >= re.rows) NODATA else src.get(xsrc, ysrc)
      }
      val tre = buildTileRasterExtent(tx,ty,re,pixelCols,pixelRows)
      rasters(ty * tileCols + tx) = Raster(data, tre)
    }

    TileArrayRasterData(rasters, layout, src.rasterExtent)
  }

  /**
   * Write a TiledRasterData to disk as a tile set, using the provided path and
   * name to determine what filenames to use.
   */
  def writeTiles(data:TiledRasterData, re:RasterExtent, name:String, path:String) = {

    val f = new java.io.File(path)
    val ok = if (f.exists) f.isDirectory else f.mkdirs
    if (!ok) sys.error("couldn't create directory %s" format path)

    val tiles = data.getTiles(re)
    for (row <- 0 until data.tileRows; col <- 0 until data.tileCols) {  
      val i = row * data.tileCols + col
      val r = tiles(i)
      val name2 = tileName(name, col, row)
      val path2 = tilePath(path, name, col, row)

      ArgWriter(data.getType).write(path2, r, name2)
    }

    writeLayout(data.getType, data.tileLayout, re, name, path)
  }

  def writeLayout(rasterType:RasterType, 
                  tileLayout:TileLayout, 
                  re:RasterExtent, 
                  name:String, 
                  path:String) = {
    val RasterExtent(Extent(xmin, ymin, xmax, ymax), cw, ch, _, _) = re
    val TileLayout(lcols, lrows, pcols, prows) = tileLayout

    val layout = """{
  "layer": "%s",

  "type": "tiled",
  "datatype": "%s",

  "xmin": %f,
  "xmax": %f,
  "ymin": %f,
  "ymax": %f,

  "cellwidth": %f,
  "cellheight": %f,

  "tile_base": "%s",
  "layout_cols": %d,
  "layout_rows": %d,
  "pixel_cols": %d,
  "pixel_rows": %d,

  "yskew": 0.0,
  "xskew": 0.0,
  "epsg": 3785
}""".format(name, rasterType.name, xmin, xmax, ymin, ymax, cw, ch, name, lcols, lrows, pcols, prows)

    val layoutPath = Filesystem.join(path, "layout.json")
    val bos = new BufferedOutputStream(new FileOutputStream(layoutPath))
    bos.write(layout.getBytes)
    bos.close
  }

  /**
   * Write a TiledRasterData to disk as a tile set, creating each tile's data
   * by executing a function that returns a raster.
   *
   * Note that the function will need to generate its RasterExtent from the ResolutionLayout,
   * e.g.
   * val rl = tileLayout.getResolutionLayout(re)
   * val tileRasterExtent = rl.getRasterExtent(col, row)
   */ 
  def writeTilesFromFunction(pixelCols:Int, pixelRows:Int, re:RasterExtent, name:String, path:String,
    f:(Int,Int,TileLayout,RasterExtent) => Raster) {
    val layout = buildTileLayout(re, pixelCols, pixelRows)
    for (row <- 0 until layout.tileRows; col <- 0 until layout.tileCols) {
      val raster = f(col, row, layout, re)
      val name2 = tileName(name, col, row)
      val path2 = tilePath(path, name, col, row) 
      ArgWriter(raster.data.getType).write(path2, raster, name2)
    }
  }

  def writeTilesWithGdal(inPath: String, name:String, outputDir:String, pixelCols:Int, pixelRows:Int) {
    val rasterInfo = Gdal.info(inPath)
    val re = rasterInfo.rasterExtent

    val rasterType = rasterInfo.rasterType match {
      case Some(rt) => rt
      case None => TypeDouble   // What should be default if source raster data file has unsupported type?
    }
    val layout = buildTileLayout(re, pixelCols, pixelRows)

    println("Writing layout...")
    val dir = new java.io.File(outputDir)
    if (!dir.exists()) dir.mkdir()
    writeLayout(rasterType, layout, re, name, outputDir)

    val reLayout = layout.getResolutionLayout(re)
    for (row <- 0 until layout.tileRows; col <- 0 until layout.tileCols) {
      val name2 = tileName (name, col, row)
      val outputPath = tilePath(outputDir, "tmp_" + name, col, row)  
      val re = reLayout.getRasterExtent(col,row)

      ArgWriter(rasterType).writeMetadataJSON(outputPath, name2,re)
      Gdal.translate(inPath, outputPath, rasterType, col * pixelCols, row * pixelRows, pixelCols, pixelRows)

      val arg = new ArgReader(outputPath).readPath(None,None)
      var nodata = true
      val outArg = arg.mapIfSet { z => {
          if (z == NODATA + 1) NODATA else { if (z != NODATA) nodata = false; z }
        }
      }
      val finalPath = tilePath(outputDir, name, col, row)
      if (nodata) {
        val jsonPath = Filesystem.join(outputDir, tileName(name, col, row)) + ".json"
        ArgWriter(rasterType).writeMetadataJSON(jsonPath, name2,re)
      } else {
        ArgWriter(rasterType).write(finalPath, outArg, name2)
      }
      
      // Clean up temporary files
      new java.io.File(outputPath).delete() ; new java.io.File(outputPath.replace(".arg", ".json")).delete()
    }
  } 

  /**
   * Given a path and name, deletes the relevant tileset from the disk.
   */  
  def deleteTiles(tiles:TiledRasterData, name:String, path:String) {
    for (row <- 0 until tiles.tileRows; col <- 0 until tiles.tileCols) {  
      val f = new java.io.File(tilePath(path, name, col, row))
      try {
        f.delete
      } catch {
        case e:Exception => {}
      }
    }
  }
}
