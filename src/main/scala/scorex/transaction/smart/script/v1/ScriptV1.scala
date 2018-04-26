package scorex.transaction.smart.script.v1

import com.wavesplatform.crypto
import com.wavesplatform.lang.ScriptVersion.Versions.V1
import com.wavesplatform.lang.v1.FunctionHeader.FunctionHeaderType.BYTEVECTOR
import com.wavesplatform.lang.v1.Terms.{BOOLEAN, Typed}
import com.wavesplatform.lang.v1.ctx.Context
import com.wavesplatform.lang.v1.{FunctionHeader, ScriptEstimator, Serde}
import com.wavesplatform.state.ByteStr
import monix.eval.Coeval
import scorex.transaction.smart.script.Script

object ScriptV1 {
  private val functionCosts: Map[FunctionHeader, Long] = Context.functionCosts(com.wavesplatform.utils.dummyContext.functions.values)

  private val checksumLength = 4
  private val maxComplexity  = 20 * functionCosts(FunctionHeader("sigVerify", List(BYTEVECTOR, BYTEVECTOR, BYTEVECTOR)))
  private val maxSizeInBytes = 2 * 1024

  def validateBytes(bs: Array[Byte]): Either[String, Unit] =
    Either.cond(bs.length <= maxSizeInBytes, (), s"Script is too large: ${bs.length} bytes > $maxSizeInBytes bytes")

  def apply(x: Typed.EXPR, checkBytes: Boolean = true): Either[String, Script] =
    for {
      _                <- Either.cond(x.tpe == BOOLEAN, (), "Script should return BOOLEAN")
      scriptComplexity <- ScriptEstimator(functionCosts, x)
      _                <- Either.cond(scriptComplexity <= maxComplexity, (), s"Script is too complex: $scriptComplexity > $maxComplexity")
      s = new ScriptV1(x)
      _ <- if (checkBytes) validateBytes(s.bytes().arr) else Right(())
    } yield s

  private class ScriptV1(override val expr: Typed.EXPR) extends Script {
    override type V = V1.type
    override val version: V   = V1
    override val text: String = expr.toString
    override val bytes: Coeval[ByteStr] =
      Coeval.evalOnce {
        val s = Array(version.value.toByte) ++ Serde.codec.encode(expr).require.toByteArray
        ByteStr(s ++ crypto.secureHash(s).take(ScriptV1.checksumLength))
      }
  }
}
