package ccn

import java.io._
import com.typesafe.scalalogging.slf4j.Logging
import nfn.NodeConfig
import ccn.packet.CCNName

/**
 * Pipes a [[InputStream]] to a file with the given name into ./log/<name>.log.
 * If you don't want the logfile to be overridden, set appendSystemTime to true.
 * @param is input stream to pipe the content to a file
 * @param logname the name of the file (./log/logname)
 * @param appendTimestamp Avoids overriding of the file by writing to ./log/<logname>-timestamp.log instead
 */
class LogStreamReaderToFile(is: InputStream, logname: String, appendTimestamp: Boolean = false) extends Runnable {

  private val reader = new BufferedReader(new InputStreamReader(is))
  val filename = {
    if (appendTimestamp) s"./log/$logname.log"
    else s"./log/$logname-${System.currentTimeMillis}.log"
  }

  private val writer = new BufferedWriter(new FileWriter(new File(filename)))

  override def run() = {
    try {
      var line = reader.readLine
      while (line != null) {
        writer.write(line + "\n")
        line = reader.readLine
      }
    } finally {
      reader.close()
      writer.close()
    }
  }
}

/**
 * Encapsulates a native ccn-lite-relay process.
 * Starts a process with the given port and sets up a compute port. All output is written to a file in './log/ccnlite-<host>-<port>.log.
 * Call start and stop.
 * @param nodeConfig
 */
case class CCNLiteProcess(nodeConfig: NodeConfig) extends Logging {

  case class NetworkFace(toHost: String, toPort: Int) {
    private val cmdUDPFace = s"../util/ccn-lite-ctrl -x $sockName newUDPface any $toHost $toPort"
    println(s"CCNLiteProcess-$prefix: executing '$cmdUDPFace")
    Runtime.getRuntime.exec(cmdUDPFace.split(" "))
    udpFaces += (toHost -> toPort) -> this

    private val networkFaceId: Int = globalFaceId
    globalFaceId += 2

    def registerPrefix(prefixToRegister: String) = {
      val cmdPrefixReg =  s"../util/ccn-lite-ctrl -x $sockName prefixreg $prefixToRegister $networkFaceId"
      println(s"CCNLiteProcess-$prefix: executing '$cmdPrefixReg")
      Runtime.getRuntime.exec(cmdPrefixReg.split(" "))
      globalFaceId += 1
    }
  }

  private var process: Process = null
  private var globalFaceId = 2

  val host = nodeConfig.host
  val port = nodeConfig.port
  val computePort = nodeConfig.computePort
  val prefix = nodeConfig.prefix.toString

  val sockName = s"/tmp/mgmt.${nodeConfig.prefix.cmps.mkString(".")}.sock"

  var udpFaces:Map[(String, Int), NetworkFace] = Map()

  def start() = {
//    if(port != 10020) {

    val cmd = s"../ccn-lite-relay -v 99 -u $port -x $sockName"
    println(s"CCNLiteProcess-$prefix: executing: '$cmd'")
    val processBuilder = new ProcessBuilder(cmd.split(" "): _*)
    processBuilder.redirectErrorStream(true)
    process = processBuilder.start

    val lsr = new LogStreamReaderToFile(process.getInputStream, s"ccnlite-$host-$port", appendTimestamp = true)
    val thread = new Thread(lsr, s"LogStreamReader-$prefix")
    thread.start

//    }
    globalFaceId = 2
  }

  def stop() = {
    println(s"CCNLiteProcess-$prefix: stop")
    if (process != null) {
      process.destroy()
    }
  }

  private def getOrCreateNetworkFace(host: String, port: Int): NetworkFace = {
    udpFaces.getOrElse(host -> port, NetworkFace(host, port))
  }

  private def addPrefixToNewOrExistingNetworkFace(host: String, port: Int, prefix: String) = {
    val networkFace = getOrCreateNetworkFace(host, port)

    networkFace.registerPrefix(prefix)
  }

  def connect(otherNodeConfig: NodeConfig): Unit = {
    println(s"Connecting $nodeConfig to $otherNodeConfig")
    addPrefixToNewOrExistingNetworkFace(otherNodeConfig.host, otherNodeConfig.port, otherNodeConfig.prefix.toString)
  }


  def addPrefix(prefix: CCNName, gatewayHost: String, gatewayPort: Int) = {
    println(s"Adding prefix $prefix to gateway ($gatewayHost:$gatewayPort)")
    addPrefixToNewOrExistingNetworkFace(gatewayHost, gatewayPort, prefix.toString)
  }
}
