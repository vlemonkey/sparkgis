package cn.com.gis.etl.shanghai.function

import java.text.SimpleDateFormat

import com.utils.ConfigUtils
import scala.math._
import scala.collection.mutable.ArrayBuffer

/**
 * Created by wangxy on 15-11-12.
 */
object fingergis1 {

  val propFile = "/config/shanghai.properties"
  val prop = ConfigUtils.getConfig(propFile)
  val finger_line_max_num = prop.getOrElse("FINGER_LINE_MAX_NUM", "12").toInt
  val rsrp_down_border = prop.getOrElse("RSRP_DOWN_BORDER", "-100").toInt
  val grip_size = prop.getOrElse("GRID_SIZE", "25").toInt
  val rssi_uplimit = prop.getOrElse("RSSI_UPLIMIT", "-40").toInt
  val rssi_downlimit = prop.getOrElse("RSSI_DOWNLIMIT", "-100").toInt
  val bseq_index = prop.getOrElse("SEQ_INDEX", "6").toInt
  val bdiff_value = prop.getOrElse("DIFF_VALUE", "12").toInt
//  val bmaxdiff_value = prop.getOrElse("MAXDIFF_VALUE", "97").toInt
  val isfilter_by_mcell = prop.getOrElse("ISFILTER_BY_MCELL", "1")
  val filterByDistance_percent = prop.getOrElse("FILTERBYDISTANCE_PERCENT", "0").toFloat
  //1:方差 2:绝对平均差 3:相关系数
  val calculate_choice = prop.getOrElse("CALCULATE_CHOICE", "1").toInt
  val samefactor_limit = prop.getOrElse("SAMEFACTOR_LIMIT", "1").toInt
  val variance_limit = prop.getOrElse("VARIANCE_LIMIT", "99999999").toInt
  val averdiff_limit = prop.getOrElse("AVERDIFF_LIMIT", "97").toInt
  val similar_limit = prop.getOrElse("SIMILAR_LIMIT", "0").toInt
  val istwice_compare = prop.getOrElse("ISTWICE_COMPARE", "0").toInt
  val twicedistance_limit = prop.getOrElse("TWICEDISTANCE_LIMIT", "9999.0").toFloat
  val twicetime_limit = prop.getOrElse("TWICETIME_LIMIT", "3000.0").toLong


  // 电频值在范围内
  def rejectByRssi(info: ArrayBuffer[String]): Boolean = {
//    println("rsrp="+info(3))
//    if(info(3) != "" && info(3).toInt > rssi_downlimit && info(3).toInt < rssi_uplimit){
//      println("true")
//      true
//    }else{
//      println("false")
//      false
//    }
    info(3) != "" && info(3).toInt >= rssi_downlimit && info(3).toInt <= rssi_uplimit
  }

  // !!!!! 调这个函数时scandata是有序的,根据rssi由强到弱
  def getCandidateFinger(fingerprint: Array[(String, Array[Array[String]])], scandata: ArrayBuffer[ArrayBuffer[String]], tag: String):
  ArrayBuffer[(String, Array[Array[String]])] = {
    // 此处不区分指纹和数据各有几个主服务小区
    var finger = ArrayBuffer[(String, Array[Array[String]])]()
    var bfirst2 = true
    // 根据主服务小区匹配指纹库中主服务小区相同的记录 文档中的步骤2
    if(tag == "1"){
      scandata.foreach(x => {
        if(x(2) == "1"){
          fingerprint.foreach(y => {
//            for(i <- 0 to (finger_line_max_num-1)){
            for(i <- 0 to (y._2.length-1)){
//              println("y._2(i)="+y._2(i).mkString(","))
              var bfirst1 = true
              if(y._2(i)(2)=="1" && y._2(i)(0)==x.head && bfirst1){
                finger += y
                bfirst1 = false
              }
            }
          })
        }
      })
    }
    // 文档中步骤3,4,5
    if(finger.size == 0){
      // 两个门限值
      var seq_index = bseq_index
      var diff_value = bdiff_value
      while(seq_index <= finger_line_max_num && diff_value < 97 && finger.size == 0){
        scandata.foreach(x =>{
          if(finger.size == 0){
            fingerprint.foreach(y => {
              var bfirst2 = true
              var index = 1
              y._2.foreach(z => {
                if(bfirst2 && z(3) != "" && x(3) != "" && z(0)==x.head && index <= seq_index && abs(z(3).toInt-x(3).toInt)<diff_value){
                  finger += y
                  index += 1
                  bfirst2 = false
                }
              })
            })
          }
        })
        if(finger.size == 0){
          seq_index += 1
          diff_value += 2
        }
      }
    }
    finger
  }

  def getCorePoint(finger: ArrayBuffer[(String, Array[Array[String]])]): (Int, Int) = {
    var px = 0
    var py = 0
    finger.foreach(x => {
      val pxy = x._1.split("\\|", -1)
      px += pxy(0).toInt
      py += pxy(1).toInt
    })
    px /= finger.size
    py /= finger.size
    (px, py)
  }

  def getDistance(p1: (Int, Int), p2: (Int, Int)): Double = {
    sqrt(pow(p1._1-p2._1,2)+pow(p1._2-p2._2,2))
  }

  def filterByDistance(finger: ArrayBuffer[(String, Array[Array[String]])], point: (Int, Int)): ArrayBuffer[(String, Array[Array[String]], Double)] = {
    val fingerD = finger.map(x => {
      val pxy = x._1.split("\\|", -1)
      val d = getDistance((pxy(0).toInt,pxy(1).toInt), point)
      (x._1, x._2, d)
    })
    val num = floor(finger.size * filterByDistance_percent).toInt
    fingerD.sortBy(_._3).reverse.slice(0, finger.size-num)
  }

  def getCommonByFlag(finger: Array[Array[String]], scandata: ArrayBuffer[ArrayBuffer[String]]): (ArrayBuffer[ArrayBuffer[String]], ArrayBuffer[Array[String]]) = {
    val cfinger = ArrayBuffer[Array[String]]()
    val cdata = ArrayBuffer[ArrayBuffer[String]]()
    scandata.foreach(x => {
      finger.foreach(y => {
        if(x.head==y(0)){
          cdata += x
          cfinger += y
        }
      })
    })
//    println("cdata="+cdata.map(_.mkString(",")).mkString("&"))
//    println("cfinger="+cfinger.map(_.mkString(",")).mkString("&"))
    (cdata, cfinger)
  }

  // 提取数据指纹和库指纹中各对应纹线的rsrp
  def listToArray(finger: ArrayBuffer[Array[String]], scandata: ArrayBuffer[ArrayBuffer[String]]): (Array[Double], Array[Double]) = {
    val dfinger = ArrayBuffer[Double]()
    val ddata = ArrayBuffer[Double]()
    for(i <- 0 to (finger.length-1)){
      dfinger += finger(i)(3).toDouble
      ddata += scandata(i)(3).toDouble
    }
    (ddata.toArray, dfinger.toArray)
  }

  // 计算方差
  def getVariance(inputData: Array[Double]): Double = {
    val average = inputData./:(0.0)(_+_) / inputData.length
    var result = 0.0
    inputData.foreach{x =>
      result += pow(x-average, 2)
    }
    // 原来是长度减1 这里根据公式把减1去掉
    val res = result / inputData.length
    res
  }

  // 计算相似系数
  def getCorrcoef(finger: Array[Double], scandata: Array[Double]): Double ={
    val averageT = scandata./:(0.0)(_+_) / scandata.length
    val averageL = finger./:(0.0)(_+_) / finger.length
    val cov = ArrayBuffer[Double]()
    for(i <- 0 to (scandata.length - 1)){
      cov += (scandata(i) - averageT) * (finger(i) -averageL)
    }
    var deviation = sqrt(getVariance(scandata)) * sqrt(getVariance(finger))
    if(deviation < 0.0000001)
      deviation = 0.0001
    val res = cov./:(0.0)(_+_) / cov.length / deviation
    res
  }

  def getDifByRssi(finger: Array[Double], scandata: Array[Double]): Array[Double] = {
    var dif = ArrayBuffer[Double]()
    for(i <- 0 to (finger.length-1)){
      dif += scandata(i) - finger(i)
    }
    dif.toArray
  }

  //计算 相同系数 方差 平均绝对差 相似系数
  // 返回值 Array[(栅格, Array[纹线], 距离中心点距离, 相同系数, 方差or平均绝对差or 相似系数)]
  def CalculateVarDiffSim(finger: ArrayBuffer[(String, Array[Array[String]], Double)], scandata: ArrayBuffer[ArrayBuffer[String]], flag: Int): ArrayBuffer[(String, Array[Array[String]], Double, Double, Double)] = {
    finger.map(x => {
      val (cdata, cfinger) = getCommonByFlag(x._2, scandata)
      val sameFactor = cdata.size * 1.0 / min(x._2.length, scandata.size)
      val (ddata, dfinger) = listToArray(cfinger, cdata)
      var res = -1.0
      flag match {
        case 1 => {
          // 平均绝对差
          res = getDifByRssi(dfinger, ddata)./:(0.0){_ + abs(_)} / dfinger.length
          if(res > averdiff_limit)
            res = -1.0
        }
        case 2 => {
          // 方差
          if(dfinger.length > 1)
            res = getVariance(getDifByRssi(ddata, dfinger))
          else
            res = abs(getDifByRssi(ddata, dfinger)(0))
          if(res > variance_limit)
            res = -1.0
        }
        case 3 => {
          // 相似系数
          res = getCorrcoef(dfinger, ddata)
          if(res <= similar_limit)
            res = -1.0
        }
        case _ => None
      }
      (x._1, x._2, x._3, sameFactor, res)
    })
  }

  def location(key: String, Iter: Iterable[(Array[String], ArrayBuffer[ArrayBuffer[String]])],
               fingerInfo: Array[(String, Array[Array[String]])]): String = {
//    println("key="+key)
    var lasttime = 0L
    var osg = "-1|-1"
    Iter.toList.sortBy(_._1(0)).map(x => {
//      println("cominfo="+x._1.mkString(","))
//      println("gisinfo="+x._2.map{_.mkString(",")}.mkString("$"))
      var sg = "-1|-1"
      // mr数据处理
      val scandata = x._2
      val scandata1 = scandata.filter(rejectByRssi).sortBy(_(3)).reverse
      println("scandata1=" + scandata1.map(x => x.mkString(",")).mkString("^"))
//      println("scandata1.size="+ scandata1.size)
//      println("fingerInfo="+ fingerInfo(0)._1 + "," + fingerInfo(0)._2.map(_.mkString(",")).mkString("$"))
      // 指纹数据处理 !!!!scandata是有序的,根据rssi由强到弱
      val finger = getCandidateFinger(fingerInfo, scandata1, isfilter_by_mcell)
      if(finger.size != 0 && scandata1.size != 0){
//        println("finger1=" + finger.map(x => x._2.map(_.mkString(",")).mkString("^")).mkString("\n"))
        val pxy = getCorePoint(finger)
        val afinger = filterByDistance(finger, pxy)
//        println("finger2=" + afinger.map(x => x._2.map(_.mkString(",")).mkString("^")).mkString("\n"))
        if (afinger.length != 0) {
          // 开始计算方差 绝对差 相似系数
          val ffinger = CalculateVarDiffSim(afinger, scandata1, calculate_choice)
          println("finger3=" + ffinger.map(v => v._2.map(_.mkString(",")).mkString("^") + "   distance=" + v._3 + "  samefactor=" + v._4 + "  res=" + v._5 + " fsg="+v._1+" dsg="+x._1(1)).mkString("\n"))
          println("calculate_choice="+calculate_choice)
          calculate_choice match {
            case 3 => {
              // 相似系数越大越好
              sg = ffinger.sortBy(_._5).reverse.head._1
            }
            case _ => {
              // 方差和平均绝对差越小越好
              sg = ffinger.sortBy(_._5).head._1
            }
          }
          if (istwice_compare == 1 && sg != "-1|-1") {
            val sdf = new SimpleDateFormat("yyyyMMddmmss")
            val nowtime = sdf.parse(x._1(0)).getTime
            if (0 == lasttime) {
              lasttime = nowtime
              osg = sg
            } else {
              if (abs(nowtime - lasttime) <= twicetime_limit) {
                //var (nx, ny) = ("-1", "-1")
                val nxy = sg.split("\\|", -1)
                val oxy = osg.split("\\|", -1)
                val d = sqrt(pow(nxy(0).toFloat - oxy(0).toFloat, 2) + pow(nxy(1).toFloat - oxy(1).toFloat, 2))
                if (d > twicedistance_limit) {
                  val (fx, fy) = (((nxy(0).toFloat + oxy(0).toFloat) / 2).toInt.toString, ((nxy(1).toFloat + oxy(1).toFloat) / 2).toInt.toString)
                  sg = Array[String](fx, fy).mkString("|")
                  osg = sg
                }
              }
            }
          }

          // 临时算距离
          val nxy = sg.split("\\|", -1)
          val sxy = x._1(1).split("\\|", -1)
          val tmpd = abs(nxy(0).toInt - sxy(0).toInt) + abs(nxy(1).toInt - sxy(1).toInt)
          Array[String](x._1(1), sg, tmpd.toString, x._1(2)).mkString(",")
        } else{
          "-1,-1"
        }
      }else{
        "-1,-1"
      }
    }).mkString("\n")
  }
}
