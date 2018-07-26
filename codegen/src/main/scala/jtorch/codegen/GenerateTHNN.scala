package jtorch.codegen

import java.io._
import scala.collection._
import scala.io._

/**
 * Generates Java wrapper code for THNN.h.
 * @author Tongfei Chen
 */
object GenerateTHNN extends App {

  val thnnHeader = "include/THNN/generic/THNN.h"
  val outputDir = "java/src/main/java/jtorch/nn"

  case class Argument(cType: String, name: String, comment: String)
  case class Function(className: String, name: String, src: String => String)
  case class Module(className: String, funcs: Seq[Function])

  def mapToJavaType(cType: String, floatType: String) = cType match {
    case "THTensor" => s"${floatType}Tensor"
    case "THIndexTensor" => "LongTensor"
    case "THIntegerTensor" => "IntTensor"
    case "THGenerator" => "Generator"
    case "bool" => "boolean"
    case "int64_t" => "long"
    case "accreal" => "double"
    case t => t
  }

  def parseAPI(ss: Seq[String]) = {

    val l0rx = "TH_API (.*) THNN_\\((.*)_(.*)\\)\\(".r
    val l0rx(resultCType, className, functionName) = ss.head

    val argRx = "\\s+([^/]*?)(?: \\*|\\* | )([^/]*?)\\)?[,;](.*)".r
    val commentRx = ".*//(.*)".r

    val arguments = ss.tail.flatMap {
      case argRx(cType, name, rest) => rest match {
        case argRx(cType2, name2, rest2) => rest2 match {
          case argRx(cType3, name3, rest3) => rest3 match {
            case commentRx(comment) => Seq(Argument(cType, name, comment), Argument(cType2, name2, comment), Argument(cType3, name3, comment))
            case _ => Seq(Argument(cType, name, ""), Argument(cType2, name2, ""), Argument(cType3, name3, ""))
          }
          case commentRx(comment) => Seq(Argument(cType, name, comment), Argument(cType2, name2, comment))
          case _ => Seq(Argument(cType, name, ""), Argument(cType2, name2, ""))
        }
        case commentRx(comment) => Seq(Argument(cType, name, comment))
        case _ => Seq(Argument(cType, name, ""))
      }
    }
    
    Function(className, functionName, (floatType: String) =>
      s"""
        |    /**
        |${arguments.filter(a => a.cType != "THNNState").map(a => s"     * @param ${a.name} ${a.comment}").mkString("\n")}
        |     */
        |    public static ${mapToJavaType(resultCType, floatType)} $functionName(${
        arguments.filter(_.cType != "THNNState").map(a => s"${mapToJavaType(a.cType, floatType)} ${a.name}").mkString(", ")}) {
        |        TH.THNN_$floatType${className}_$functionName(${arguments.map {a => a.cType match {
        case "THNNState" => "State.INSTANCE"
        case _ => a.name
      }}.mkString(", ")});
        |    }
      """.stripMargin)
  }

  val functions = mutable.ArrayBuffer[Function]()
  val buf = mutable.ArrayBuffer[String]()
  for (l <- Source.fromFile(thnnHeader).getLines().filter(l => !l.startsWith("#") && !l.startsWith("//") && l.trim() != "")) {
    if (l startsWith "TH_API") {
      if (buf.nonEmpty) functions += parseAPI(buf)
      buf.clear()
      buf += l
    }
    else buf += l
  }

  val modules = functions.groupBy(_.className).map { case (className, funcs) => Module(className, funcs) }

  def writeModule(module: Module, floatType: String) = {
    val pw = new PrintWriter(s"$outputDir/$floatType${module.className}.java")
    val src =
      s"""
        |// Generated by jtorch.codegen.GenerateTHNN.scala.
        |// DO NOT MODIFY.
        |
        |package jtorch.nn;
        |
        |import jtorch.*;
        |import jtorch.jni.*;
        |
        |public class $floatType${module.className} {
        | ${module.funcs.map(f => f.src(floatType)).mkString("\n\n")}
        |}
      """.stripMargin
    pw.write(src)
    pw.close()
  }

  for (module <- modules) {
    writeModule(module, "Float")
    println(s"Float${module.className}.java generated.")
    writeModule(module, "Double")
    println(s"Double${module.className}.java generated.")
  }

}
