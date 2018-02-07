package scorex.wallet

import java.io.{File, FileInputStream}
import java.security.KeyStore

import com.google.common.primitives.{Bytes, Ints}
import com.wavesplatform.settings.WalletSettings
import com.wavesplatform.state2.ByteStr
import com.wavesplatform.utils.JsonFileStorage
import play.api.libs.json._
import scorex.account.{Address, PrivateKeyAccount}
import scorex.crypto.hash.SecureCryptographicHash
import scorex.transaction.ValidationError
import scorex.transaction.ValidationError.MissingSenderPrivateKey
import scorex.utils.{ScorexLogging, randomBytes}

import scala.collection.concurrent.TrieMap
import scala.util.control.NonFatal
import com.wavesplatform.utils._
import ru.CryptoPro.JCP.JCP

trait Wallet {

  def seed: Array[Byte]

  def nonce: Int

  def privateKeyAccounts: List[PrivateKeyAccount]

  def generateNewAccounts(howMany: Int): Seq[PrivateKeyAccount]

  def generateNewAccount(): Option[PrivateKeyAccount]

  def deleteAccount(account: PrivateKeyAccount): Boolean

  def privateKeyAccount(account: Address): Either[ValidationError, PrivateKeyAccount]

}

object Wallet extends ScorexLogging {

  implicit class WalletExtension(w: Wallet) {
    def findWallet(addressString: String): Either[ValidationError, PrivateKeyAccount] = for {
      acc <- Address.fromString(addressString)
      privKeyAcc <- w.privateKeyAccount(acc)
    } yield privKeyAcc

    def exportAccountSeed(account: Address): Either[ValidationError, Array[Byte]] = w.privateKeyAccount(account).map(_.seed)
  }

  def generateNewAccount(seed: Array[Byte], nonce: Int): PrivateKeyAccount = {
    val accountSeed = generateAccountSeed(seed, nonce)
    PrivateKeyAccount(accountSeed)
  }

  def generateAccountSeed(seed: Array[Byte], nonce: Int): Array[Byte] =
    SecureCryptographicHash(Bytes.concat(Ints.toByteArray(nonce), seed))

  def apply(settings: WalletSettings): Wallet = new WalletImpl(settings.file, settings.password, settings.seed)

  private class WalletImpl(file: Option[File], password: String, seedFromConfig: Option[ByteStr])
    extends ScorexLogging with Wallet {

    private val keyStore = KeyStore.getInstance(JCP.HD_STORE_NAME, JCP.PROVIDER_NAME)
    file match {
      case Some(f) if f.exists() =>
        keyStore.load(new FileInputStream(f), password)
      case Some(f) if !f.exists() =>
        f.getParentFile.mkdirs()
        f.createNewFile()
        keyStore.load(null, null)
      case _ => keyStore.load(null, null)
    }

    private def loadOrImport(f: File) = try {
      Some(JsonFileStorage.load[WalletData](f.getCanonicalPath, Some(key)))
    } catch {
      case NonFatal(_) => None
    }

    private var walletData = file.flatMap(loadOrImport).getOrElse(WalletData(actualSeed, Set.empty, 0))

    private val l = new Object

    private def lock[T](f: => T): T = l.synchronized(f)

    private val accountsCache: TrieMap[String, PrivateKeyAccount] = {
      val accounts = walletData.accountSeeds.map(seed => PrivateKeyAccount(seed.arr))
      TrieMap(accounts.map(acc => acc.address -> acc).toSeq: _*)
    }

    private def save(): Unit = file.foreach(f => JsonFileStorage.save(walletData, f.getCanonicalPath, Some(key)))

    private def generateNewAccountWithoutSave(): Option[PrivateKeyAccount] = lock {
      val nonce = getAndIncrementNonce()
      val account = Wallet.generateNewAccount(seed, nonce)

      val address = account.address
      if (!accountsCache.contains(address)) {
        accountsCache += account.address -> account
        walletData = walletData.copy(accountSeeds = walletData.accountSeeds + ByteStr(account.seed))
        log.info("Added account #" + privateKeyAccounts.size)
        Some(account)
      } else None
    }

    override def seed: Array[Byte] = walletData.seed.arr

    override def privateKeyAccounts: List[PrivateKeyAccount] = accountsCache.values.toList

    override def generateNewAccounts(howMany: Int): Seq[PrivateKeyAccount] =
      (1 to howMany).flatMap(_ => generateNewAccountWithoutSave()).tap(_ => save())

    override def generateNewAccount(): Option[PrivateKeyAccount] = lock {
      generateNewAccountWithoutSave().map(acc => {save(); acc})
    }

    override def deleteAccount(account: PrivateKeyAccount): Boolean = lock {
      val before = walletData.accountSeeds.size
      walletData = walletData.copy(accountSeeds = walletData.accountSeeds - ByteStr(account.seed))
      accountsCache -= account.address
      save()
      before > walletData.accountSeeds.size
    }

    override def privateKeyAccount(account: Address): Either[ValidationError, PrivateKeyAccount] =
      accountsCache.get(account.address).toRight[ValidationError](MissingSenderPrivateKey)

    override def nonce: Int = walletData.nonce

    private def getAndIncrementNonce(): Int = lock {
      val r = walletData.nonce
      walletData = walletData.copy(nonce = walletData.nonce + 1)
      r
    }
  }
}
