package com.wavesplatform

import cats.syntax.semigroup._
import com.wavesplatform.lang.Global
import com.wavesplatform.lang.v1.Terms.Typed
import com.wavesplatform.lang.v1.TypeChecker
import com.wavesplatform.lang.v1.ctx.impl.{CryptoContext, PureContext}
import com.wavesplatform.lang.v1.testing.ScriptGen
import com.wavesplatform.settings.Constants
import com.wavesplatform.state._
import com.wavesplatform.state.diffs.ENOUGH_AMT
import org.scalacheck.Gen.{alphaLowerChar, alphaUpperChar, frequency, numChar}
import org.scalacheck.{Arbitrary, Gen}
import org.scalatest.{BeforeAndAfterAll, Suite}
import scorex.account.PublicKeyAccount._
import scorex.account._
import scorex.transaction._
import scorex.transaction.assets.MassTransferTransaction.ParsedTransfer
import scorex.transaction.assets._
import scorex.transaction.assets.exchange._
import scorex.transaction.lease.{LeaseCancelTransaction, LeaseTransaction}
import scorex.transaction.smart.script.Script
import scorex.transaction.smart.script.v1.ScriptV1
import scorex.transaction.smart.{SetScriptTransaction}
import scorex.utils.TimeImpl

import scala.util.Random

trait TransactionGen extends BeforeAndAfterAll with TransactionGenBase with ScriptGen {
  _: Suite =>
  override protected def afterAll(): Unit = {
    super.close()
  }
}

trait TransactionGenBase extends ScriptGen {

  protected def waves(n: Float): Long = (n * 100000000L).toLong

  def byteArrayGen(length: Int): Gen[Array[Byte]] = Gen.containerOfN[Array, Byte](length, Arbitrary.arbitrary[Byte])

  val bytes32gen: Gen[Array[Byte]] = byteArrayGen(32)
  val bytes64gen: Gen[Array[Byte]] = byteArrayGen(64)

  def genBoundedBytes(minSize: Int, maxSize: Int): Gen[Array[Byte]] =
    for {
      length <- Gen.chooseNum(minSize, maxSize)
      bytes  <- byteArrayGen(length)
    } yield bytes

  def genBoundedString(minSize: Int, maxSize: Int): Gen[Array[Byte]] = {
    Gen.choose(minSize, maxSize) flatMap { sz =>
      Gen.listOfN(sz, Gen.choose(0, 0x7f).map(_.toByte)).map(_.toArray)
    }
  }

  private val time = new TimeImpl

  def close(): Unit = {
    time.close()
  }

  val ntpTimestampGen: Gen[Long] = Gen.choose(1, 1000).map(time.correctedTime() - _)

  val accountGen: Gen[PrivateKeyAccount] = bytes32gen.map(seed => PrivateKeyAccount(seed))

  val aliasSymbolChar: Gen[Char] = Gen.oneOf('.', '@', '_', '-')

  val invalidAliasSymbolChar: Gen[Char] = Gen.oneOf('~', '`', '!', '#', '$', '%', '^', '&', '*', '=', '+')

  val aliasAlphabetGen: Gen[Char] = frequency((1, numChar), (1, aliasSymbolChar), (9, alphaLowerChar))

  val invalidAliasAlphabetGen: Gen[Char] = frequency((1, numChar), (3, invalidAliasSymbolChar), (9, alphaUpperChar))

  val validAliasStringGen: Gen[String] = for {
    length     <- Gen.chooseNum(Alias.MinLength, Alias.MaxLength)
    aliasChars <- Gen.listOfN(length, aliasAlphabetGen)
  } yield aliasChars.mkString

  val aliasGen: Gen[Alias] = for {
    str <- validAliasStringGen
  } yield Alias.buildWithCurrentNetworkByte(str.mkString).explicitGet()

  val invalidAliasStringGen: Gen[String] = for {
    length     <- Gen.chooseNum(Alias.MinLength, Alias.MaxLength)
    aliasChars <- Gen.listOfN(length, invalidAliasAlphabetGen)
  } yield aliasChars.mkString

  val accountOrAliasGen: Gen[AddressOrAlias] = Gen.oneOf(aliasGen, accountGen.map(PublicKeyAccount.toAddress(_)))

  def otherAccountGen(candidate: PrivateKeyAccount): Gen[PrivateKeyAccount] = accountGen.flatMap(Gen.oneOf(candidate, _))

  val positiveLongGen: Gen[Long] = Gen.choose(1, 100000000L * 100000000L / 100)
  val positiveIntGen: Gen[Int]   = Gen.choose(1, Int.MaxValue / 100)
  val smallFeeGen: Gen[Long]     = Gen.choose(1, 100000000)

  val maxOrderTimeGen: Gen[Long] = Gen.choose(10000L, Order.MaxLiveTime).map(_ + time.correctedTime())
  val timestampGen: Gen[Long]    = Gen.choose(1, Long.MaxValue - 100)

  val wavesAssetGen: Gen[Option[ByteStr]] = Gen.const(None)
  val assetIdGen: Gen[Option[ByteStr]]    = Gen.frequency((1, wavesAssetGen), (10, Gen.option(bytes32gen.map(ByteStr(_)))))

  val assetPairGen = assetIdGen.flatMap {
    case None => bytes32gen.map(b => AssetPair(None, Some(ByteStr(b))))
    case a1 @ Some(a1bytes) =>
      val a2bytesGen = byteArrayGen(31).map(a2bytes => Option((~a1bytes.arr(0)).toByte +: a2bytes))
      Gen.oneOf(Gen.const(None), a2bytesGen).map(a2 => AssetPair(a1, a2.map(ByteStr(_))))
  }

  val proofsGen: Gen[Proofs] = for {
    proofsAmount <- Gen.chooseNum(0, 7)
    proofs       <- Gen.listOfN(proofsAmount, genBoundedBytes(0, 50))
  } yield Proofs.create(proofs.map(ByteStr(_))).explicitGet()

  val scriptGen = BOOLgen(1000).map { expr =>
    val typed = TypeChecker(TypeChecker.TypeCheckerContext.fromContext(PureContext.instance |+| CryptoContext.build(Global)), expr).explicitGet()
    ScriptV1(typed).explicitGet()
  }

  val setScriptTransactionGen: Gen[SetScriptTransaction] = for {
    version                   <- Gen.oneOf(SetScriptTransaction.supportedVersions.toSeq)
    sender: PrivateKeyAccount <- accountGen
    fee                       <- smallFeeGen
    timestamp                 <- timestampGen
    proofs                    <- proofsGen
    script                    <- Gen.option(scriptGen)
  } yield SetScriptTransaction.create(version, sender, script, fee, timestamp, proofs).explicitGet()

  def selfSignedSetScriptTransactionGenP(sender: PrivateKeyAccount, s: Script): Gen[SetScriptTransaction] =
    for {
      version   <- Gen.oneOf(SetScriptTransaction.supportedVersions.toSeq)
      fee       <- smallFeeGen
      timestamp <- timestampGen
    } yield SetScriptTransaction.selfSigned(version, sender, Some(s), fee, timestamp).explicitGet()

  val paymentGen: Gen[PaymentTransaction] = for {
    sender: PrivateKeyAccount    <- accountGen
    recipient: PrivateKeyAccount <- accountGen
    tx                           <- paymentGeneratorP(sender, recipient)
  } yield tx

  val selfPaymentGen: Gen[PaymentTransaction] = accountGen.flatMap(acc => paymentGeneratorP(acc, acc))

  def paymentGeneratorP(sender: PrivateKeyAccount, recipient: PrivateKeyAccount): Gen[PaymentTransaction] =
    timestampGen.flatMap(ts => paymentGeneratorP(ts, sender, recipient))

  def paymentGeneratorP(timestamp: Long, sender: PrivateKeyAccount, recipient: PrivateKeyAccount): Gen[PaymentTransaction] =
    for {
      amount: Long <- positiveLongGen
      fee: Long    <- smallFeeGen
    } yield PaymentTransaction.create(sender, recipient, amount, fee, timestamp).right.get

  private val leaseParamGen = for {
    sender    <- accountGen
    amount    <- positiveLongGen
    fee       <- smallFeeGen
    timestamp <- timestampGen
    recipient <- accountGen
  } yield (sender, amount, fee, timestamp, recipient)

  val leaseAndCancelGen: Gen[(LeaseTransaction, LeaseCancelTransaction)] = for {
    (sender, amount, fee, timestamp, recipient) <- leaseParamGen
    lease = LeaseTransaction.create(sender, amount, fee, timestamp, recipient).right.get
    cancelFee <- smallFeeGen
  } yield (lease, LeaseCancelTransaction.create(sender, lease.id(), cancelFee, timestamp + 1).right.get)

  def leaseAndCancelGeneratorP(leaseSender: PrivateKeyAccount,
                               recipient: AddressOrAlias,
                               unleaseSender: PrivateKeyAccount): Gen[(LeaseTransaction, LeaseCancelTransaction)] =
    for {
      (_, amount, fee, timestamp, _) <- leaseParamGen
      lease = LeaseTransaction.create(leaseSender, amount, fee, timestamp, recipient).right.get
      fee2 <- smallFeeGen
      unlease = LeaseCancelTransaction.create(unleaseSender, lease.id(), fee2, timestamp + 1).right.get
    } yield (lease, unlease)

  val twoLeasesGen: Gen[(LeaseTransaction, LeaseTransaction)] = for {
    (sender, amount, fee, timestamp, recipient) <- leaseParamGen
    amount2                                     <- positiveLongGen
    recipient2: PrivateKeyAccount               <- accountGen
    fee2                                        <- smallFeeGen
  } yield
    (LeaseTransaction.create(sender, amount, fee, timestamp, recipient).right.get,
     LeaseTransaction.create(sender, amount2, fee2, timestamp + 1, recipient2).right.get)

  val leaseAndCancelWithOtherSenderGen: Gen[(LeaseTransaction, LeaseCancelTransaction)] = for {
    (sender, amount, fee, timestamp, recipient) <- leaseParamGen
    otherSender: PrivateKeyAccount              <- accountGen
    lease = LeaseTransaction.create(sender, amount, fee, timestamp, recipient).right.get
    fee2       <- smallFeeGen
    timestamp2 <- positiveLongGen
  } yield (lease, LeaseCancelTransaction.create(otherSender, lease.id(), fee2, timestamp2).right.get)

  val leaseGen: Gen[LeaseTransaction]             = leaseAndCancelGen.map(_._1)
  val leaseCancelGen: Gen[LeaseCancelTransaction] = leaseAndCancelGen.map(_._2)

  val transferParamGen = for {
    amount     <- positiveLongGen
    feeAmount  <- smallFeeGen
    assetId    <- Gen.option(bytes32gen)
    feeAssetId <- Gen.option(bytes32gen)
    timestamp  <- timestampGen
    sender     <- accountGen
    attachment <- genBoundedBytes(0, TransferTransaction.MaxAttachmentSize)
    recipient  <- accountOrAliasGen
  } yield (assetId.map(ByteStr(_)), sender, recipient, amount, timestamp, feeAssetId.map(ByteStr(_)), feeAmount, attachment)

  def transferGeneratorP(sender: PrivateKeyAccount,
                         recipient: AddressOrAlias,
                         assetId: Option[AssetId],
                         feeAssetId: Option[AssetId]): Gen[TransferTransaction] =
    for {
      (_, _, _, amount, timestamp, _, feeAmount, attachment) <- transferParamGen
    } yield TransferTransaction.create(assetId, sender, recipient, amount, timestamp, feeAssetId, feeAmount, attachment).right.get

  def transferGeneratorP(timestamp: Long, sender: PrivateKeyAccount, recipient: AddressOrAlias, maxAmount: Long): Gen[TransferTransaction] =
    for {
      amount                                    <- Gen.choose(1, maxAmount)
      (_, _, _, _, _, _, feeAmount, attachment) <- transferParamGen
    } yield TransferTransaction.create(None, sender, recipient, amount, timestamp, None, feeAmount, attachment).right.get

  def transferGeneratorP(timestamp: Long,
                         sender: PrivateKeyAccount,
                         recipient: AddressOrAlias,
                         assetId: Option[AssetId],
                         feeAssetId: Option[AssetId]): Gen[TransferTransaction] =
    for {
      (_, _, _, amount, _, _, feeAmount, attachment) <- transferParamGen
    } yield TransferTransaction.create(assetId, sender, recipient, amount, timestamp, feeAssetId, feeAmount, attachment).right.get

  def wavesTransferGeneratorP(sender: PrivateKeyAccount, recipient: AddressOrAlias): Gen[TransferTransaction] =
    transferGeneratorP(sender, recipient, None, None)

  def wavesTransferGeneratorP(timestamp: Long, sender: PrivateKeyAccount, recipient: AddressOrAlias): Gen[TransferTransaction] =
    transferGeneratorP(timestamp, sender, recipient, None, None)

  def massTransferGeneratorP(sender: PrivateKeyAccount, transfers: List[ParsedTransfer], assetId: Option[AssetId]): Gen[MassTransferTransaction] =
    for {
      version                                           <- Gen.oneOf(MassTransferTransaction.supportedVersions.toSeq)
      (_, _, _, _, timestamp, _, feeAmount, attachment) <- transferParamGen
    } yield MassTransferTransaction.selfSigned(version, assetId, sender, transfers, timestamp, feeAmount, attachment).right.get

  def createWavesTransfer(sender: PrivateKeyAccount,
                          recipient: Address,
                          amount: Long,
                          fee: Long,
                          timestamp: Long): Either[ValidationError, TransferTransaction] =
    TransferTransaction.create(None, sender, recipient, amount, timestamp, None, fee, Array())

  val transferGen = (for {
    (assetId, sender, recipient, amount, timestamp, feeAssetId, feeAmount, attachment) <- transferParamGen
  } yield TransferTransaction.create(assetId, sender, recipient, amount, timestamp, feeAssetId, feeAmount, attachment).right.get)
    .label("transferTransaction")

  val versionedTransferGen = (for {
    version                                                                   <- Gen.oneOf(VersionedTransferTransaction.supportedVersions.toSeq)
    (assetId, sender, recipient, amount, timestamp, _, feeAmount, attachment) <- transferParamGen
    proofs                                                                    <- proofsGen
  } yield VersionedTransferTransaction.create(version, assetId, sender, recipient, amount, timestamp, feeAmount, attachment, proofs).explicitGet())
    .label("VersionedTransferTransaction")

  def versionedTransferGenP(sender: PublicKeyAccount, recipient: Address, proofs: Proofs) =
    (for {
      version   <- Gen.oneOf(VersionedTransferTransaction.supportedVersions.toSeq)
      amt       <- positiveLongGen
      fee       <- smallFeeGen
      timestamp <- timestampGen
    } yield VersionedTransferTransaction.create(version, None, sender, recipient, amt, timestamp, fee, Array.emptyByteArray, proofs).explicitGet())
      .label("VersionedTransferTransactionP")

  val transferWithWavesFeeGen = for {
    (assetId, sender, recipient, amount, timestamp, _, feeAmount, attachment) <- transferParamGen
  } yield TransferTransaction.create(assetId, sender, recipient, amount, timestamp, None, feeAmount, attachment).right.get

  val selfTransferWithWavesFeeGen: Gen[TransferTransaction] = for {
    (assetId, sender, _, amount, timestamp, _, feeAmount, attachment) <- transferParamGen
  } yield TransferTransaction.create(assetId, sender, sender, amount, timestamp, None, feeAmount, attachment).right.get

  val selfTransferGen: Gen[TransferTransaction] = for {
    (assetId, sender, _, amount, timestamp, feeAssetId, feeAmount, attachment) <- transferParamGen
  } yield TransferTransaction.create(assetId, sender, sender, amount, timestamp, feeAssetId, feeAmount, attachment).right.get

  val massTransferGen = {
    import MassTransferTransaction.MaxTransferCount
    for {
      version                                                      <- Gen.oneOf(MassTransferTransaction.supportedVersions.toSeq)
      (assetId, sender, _, _, timestamp, _, feeAmount, attachment) <- transferParamGen
      transferCount                                                <- Gen.choose(0, MaxTransferCount)
      transferGen = for {
        recipient <- accountOrAliasGen
        amount    <- Gen.choose(1L, Long.MaxValue / MaxTransferCount)
      } yield ParsedTransfer(recipient, amount)
      recipients <- Gen.listOfN(transferCount, transferGen)
    } yield MassTransferTransaction.selfSigned(version, assetId, sender, recipients, timestamp, feeAmount, attachment).right.get
  }.label("massTransferTransaction")

  val MinIssueFee = 100000000

  val createAliasGen: Gen[CreateAliasTransaction] = for {
    timestamp: Long           <- positiveLongGen
    sender: PrivateKeyAccount <- accountGen
    alias: Alias              <- aliasGen
  } yield CreateAliasTransaction.create(sender, alias, MinIssueFee, timestamp).right.get

  val issueParamGen = for {
    sender: PrivateKeyAccount <- accountGen
    assetName                 <- genBoundedString(IssueTransaction.MinAssetNameLength, IssueTransaction.MaxAssetNameLength)
    description               <- genBoundedString(0, IssueTransaction.MaxDescriptionLength)
    quantity                  <- Gen.choose(Long.MaxValue / 200, Long.MaxValue / 100)
    decimals                  <- Gen.choose(0: Byte, 8: Byte)
    reissuable                <- Arbitrary.arbitrary[Boolean]
    fee                       <- Gen.choose(MinIssueFee, 2 * MinIssueFee)
    timestamp                 <- positiveLongGen
  } yield (sender, assetName, description, quantity, decimals, reissuable, fee, timestamp)

  val issueReissueBurnGen: Gen[(IssueTransaction, ReissueTransaction, BurnTransaction)] = for {
    amount                    <- positiveLongGen
    sender: PrivateKeyAccount <- accountGen
    r                         <- issueReissueBurnGeneratorP(amount, amount, amount, sender)
  } yield r

  def issueReissueBurnGeneratorP(issueQuantity: Long, sender: PrivateKeyAccount): Gen[(IssueTransaction, ReissueTransaction, BurnTransaction)] =
    issueReissueBurnGeneratorP(issueQuantity, issueQuantity, issueQuantity, sender)

  def issueReissueBurnGeneratorP(issueQuantity: Long,
                                 reissueQuantity: Long,
                                 burnQuantity: Long,
                                 sender: PrivateKeyAccount): Gen[(IssueTransaction, ReissueTransaction, BurnTransaction)] =
    for {
      (_, assetName, description, _, decimals, reissuable, iFee, timestamp) <- issueParamGen
      burnAmount                                                            <- Gen.choose(0L, burnQuantity)
      reissuable2                                                           <- Arbitrary.arbitrary[Boolean]
      fee                                                                   <- smallFeeGen
    } yield {
      val issue   = IssueTransaction.create(sender, assetName, description, issueQuantity, decimals, reissuable, iFee, timestamp).right.get
      val reissue = ReissueTransaction.create(sender, issue.assetId(), reissueQuantity, reissuable2, fee, timestamp).right.get
      val burn    = BurnTransaction.create(sender, issue.assetId(), burnAmount, fee, timestamp).right.get
      (issue, reissue, burn)
    }

  val issueWithInvalidReissuesGen: Gen[(IssueTransaction, ReissueTransaction, ReissueTransaction)] = for {
    (sender, assetName, description, quantity, decimals, _, iFee, timestamp) <- issueParamGen
    fee                                                                      <- smallFeeGen
  } yield {
    val issue    = IssueTransaction.create(sender, assetName, description, quantity, decimals, reissuable = true, iFee, timestamp).right.get
    val reissue1 = ReissueTransaction.create(sender, issue.assetId(), quantity, reissuable = false, fee, timestamp).right.get
    val reissue2 = ReissueTransaction.create(sender, issue.assetId(), quantity, reissuable = true, fee, timestamp + 1).right.get
    (issue, reissue1, reissue2)
  }

  def issueGen(sender: PrivateKeyAccount, fixedQuantity: Option[Long] = None): Gen[IssueTransaction] =
    for {
      (_, assetName, description, quantity, decimals, _, iFee, timestamp) <- issueParamGen
    } yield {
      IssueTransaction
        .create(sender, assetName, description, fixedQuantity.getOrElse(quantity), decimals, reissuable = false, 1 * Constants.UnitsInWave, timestamp)
        .right
        .get
    }

  val issueGen: Gen[IssueTransaction]     = issueReissueBurnGen.map(_._1)
  val reissueGen: Gen[ReissueTransaction] = issueReissueBurnGen.map(_._2)
  val burnGen: Gen[BurnTransaction]       = issueReissueBurnGen.map(_._3)

  def sponsorFeeCancelSponsorFeeGen(
      sender: PrivateKeyAccount): Gen[(IssueTransaction, SponsorFeeTransaction, SponsorFeeTransaction, SponsorFeeTransaction)] =
    for {
      (_, assetName, description, quantity, decimals, reissuable, iFee, timestamp) <- issueParamGen
      issue = IssueTransaction
        .create(sender, assetName, description, quantity, decimals, reissuable = reissuable, iFee, timestamp)
        .right
        .get
      minFee  <- smallFeeGen
      minFee1 <- smallFeeGen
      assetId = issue.assetId()
    } yield
      (issue,
       SponsorFeeTransaction.create(1, sender, assetId, Some(minFee), 1 * Constants.UnitsInWave, timestamp).right.get,
       SponsorFeeTransaction.create(1, sender, assetId, Some(minFee1), 1 * Constants.UnitsInWave, timestamp).right.get,
       SponsorFeeTransaction.create(1, sender, assetId, None, 1 * Constants.UnitsInWave, timestamp).right.get,
      )

  val sponsorFeeGen = for {
    sender        <- accountGen
    (_, tx, _, _) <- sponsorFeeCancelSponsorFeeGen(sender)
  } yield {
    tx
  }
  val cancelFeeSponsorshipGen = for {
    sender        <- accountGen
    (_, _, _, tx) <- sponsorFeeCancelSponsorFeeGen(sender)
  } yield {
    tx
  }

  val priceGen: Gen[Long]            = Gen.choose(1, 3 * 100000L * 100000000L)
  val matcherAmountGen: Gen[Long]    = Gen.choose(1, 3 * 100000L * 100000000L)
  val matcherFeeAmountGen: Gen[Long] = Gen.choose(1, 3 * 100000L * 100000000L)

  val orderTypeGen: Gen[OrderType] = Gen.oneOf(OrderType.BUY, OrderType.SELL)

  val orderParamGen = for {
    sender     <- accountGen
    matcher    <- accountGen
    pair       <- assetPairGen
    orderType  <- orderTypeGen
    price      <- priceGen
    amount     <- matcherAmountGen
    timestamp  <- timestampGen
    expiration <- maxOrderTimeGen
    matcherFee <- matcherFeeAmountGen
  } yield (sender, matcher, pair, orderType, price, amount, timestamp, expiration, matcherFee)

  val orderGen: Gen[Order] = for {
    (sender, matcher, pair, orderType, price, amount, timestamp, expiration, matcherFee) <- orderParamGen
  } yield Order(sender, matcher, pair, orderType, price, amount, timestamp, expiration, matcherFee)

  val arbitraryOrderGen: Gen[Order] = for {
    (sender, matcher, pair, orderType, _, _, _, _, _) <- orderParamGen
    price                                             <- Arbitrary.arbitrary[Long]
    amount                                            <- Arbitrary.arbitrary[Long]
    timestamp                                         <- Arbitrary.arbitrary[Long]
    expiration                                        <- Arbitrary.arbitrary[Long]
    matcherFee                                        <- Arbitrary.arbitrary[Long]
  } yield Order(sender, matcher, pair, orderType, price, amount, timestamp, expiration, matcherFee)

  val exchangeTransactionGen: Gen[ExchangeTransaction] = for {
    sender1: PrivateKeyAccount <- accountGen
    sender2: PrivateKeyAccount <- accountGen
    assetPair                  <- assetPairGen
    r                          <- exchangeGeneratorP(sender1, sender2, assetPair.amountAsset, assetPair.priceAsset)
  } yield r

  def exchangeGeneratorP(buyer: PrivateKeyAccount,
                         seller: PrivateKeyAccount,
                         amountAssetId: Option[ByteStr],
                         priceAssetId: Option[ByteStr],
                         fixedMatcherFee: Option[Long] = None): Gen[ExchangeTransaction] =
    for {
      (_, matcher, _, _, price, amount1, timestamp, expiration, genMatcherFee) <- orderParamGen
      amount2: Long                                                            <- matcherAmountGen
      matchedAmount: Long                                                      <- Gen.choose(Math.min(amount1, amount2) / 2000, Math.min(amount1, amount2) / 1000)
      assetPair = AssetPair(amountAssetId, priceAssetId)
    } yield {
      val matcherFee = fixedMatcherFee.getOrElse(genMatcherFee)
      val o1         = Order.buy(buyer, matcher, assetPair, price, amount1, timestamp, expiration, matcherFee)
      val o2         = Order.sell(seller, matcher, assetPair, price, amount2, timestamp, expiration, matcherFee)
      val buyFee     = (BigInt(matcherFee) * BigInt(matchedAmount) / BigInt(amount1)).longValue()
      val sellFee    = (BigInt(matcherFee) * BigInt(matchedAmount) / BigInt(amount2)).longValue()
      val trans =
        ExchangeTransaction.create(matcher, o1, o2, price, matchedAmount, buyFee, sellFee, (buyFee + sellFee) / 2, expiration - 100).explicitGet()

      trans
    }

  val randomTransactionGen: Gen[SignedTransaction] = (for {
    tr           <- transferGen
    (is, ri, bu) <- issueReissueBurnGen
    ca           <- createAliasGen
    xt           <- exchangeTransactionGen
    tx           <- Gen.oneOf(tr, is, ri, ca, bu, xt)
  } yield tx).label("random transaction")

  def randomTransactionsGen(count: Int): Gen[Seq[SignedTransaction]] =
    for {
      transactions <- Gen.listOfN(count, randomTransactionGen)
    } yield transactions

  val genesisGen: Gen[GenesisTransaction] = accountGen.flatMap(genesisGeneratorP)

  def genesisGeneratorP(recipient: PrivateKeyAccount): Gen[GenesisTransaction] =
    for {
      amt <- Gen.choose(1, 100000000L * 100000000L)
      ts  <- positiveIntGen
    } yield GenesisTransaction.create(recipient, amt, ts).right.get

  import DataEntry.{MaxKeySize, MaxValueSize}
  import DataTransaction.MaxEntryCount

  val dataKeyGen = for {
    size <- Gen.choose[Byte](0, MaxKeySize)
  } yield Random.nextString(size)

  val dataAsciiKeyGen = for {
    size <- Gen.choose[Byte](0, MaxKeySize)
  } yield Random.alphanumeric.take(size).mkString

  def longEntryGen(keyGen: Gen[String] = dataKeyGen) =
    for {
      key   <- keyGen
      value <- Gen.choose[Long](Long.MinValue, Long.MaxValue)
    } yield LongDataEntry(key, value)

  def booleanEntryGen(keyGen: Gen[String] = dataKeyGen) =
    for {
      key   <- keyGen
      value <- Gen.oneOf(true, false)
    } yield BooleanDataEntry(key, value)

  def binaryEntryGen(keyGen: Gen[String] = dataKeyGen) =
    for {
      key   <- keyGen
      size  <- Gen.choose(0, MaxValueSize)
      value <- byteArrayGen(size)
    } yield BinaryDataEntry(key, ByteStr(value))

  val dataEntryGen = Gen.oneOf(longEntryGen(), booleanEntryGen(), binaryEntryGen())

  val dataTransactionGen = {
    import DataTransaction.MaxEntryCount

    (for {
      sender    <- accountGen
      timestamp <- timestampGen
      size      <- Gen.choose(0, MaxEntryCount)
      data      <- Gen.listOfN(size, dataEntryGen)
      version   <- Gen.oneOf(DataTransaction.supportedVersions.toSeq)
    } yield DataTransaction.selfSigned(version, sender, data, 15000000, timestamp).right.get)
      .label("DataTransaction")
  }

  def dataTransactionGenP(sender: PrivateKeyAccount, data: List[DataEntry[_]]): Gen[DataTransaction] =
    (for {
      version   <- Gen.oneOf(DataTransaction.supportedVersions.toSeq)
      timestamp <- timestampGen
      size      <- Gen.choose(0, MaxEntryCount)
    } yield DataTransaction.selfSigned(version, sender, data, 15000000, timestamp).right.get)
      .label("DataTransactionP")

  def preconditionsTransferAndLease(typed: Typed.EXPR): Gen[(GenesisTransaction, SetScriptTransaction, LeaseTransaction, TransferTransaction)] =
    for {
      master    <- accountGen
      recipient <- accountGen
      ts        <- positiveIntGen
      genesis = GenesisTransaction.create(master, ENOUGH_AMT, ts).right.get
      setScript <- selfSignedSetScriptTransactionGenP(master, ScriptV1(typed).right.get)
      transfer  <- transferGeneratorP(master, recipient.toAddress, None, None)
      lease     <- leaseAndCancelGeneratorP(master, recipient.toAddress, master)
    } yield (genesis, setScript, lease._1, transfer)

  def smartIssueTransactionGen(senderGen: Gen[PrivateKeyAccount] = accountGen,
                               sGen: Gen[Option[Script]] = Gen.option(scriptGen)): Gen[SmartIssueTransaction] =
    for {
      version                                                                     <- Gen.oneOf(SmartIssueTransaction.supportedVersions.toSeq)
      script                                                                      <- sGen
      (_, assetName, description, quantity, decimals, reissuable, fee, timestamp) <- issueParamGen
      sender                                                                      <- senderGen
    } yield
      SmartIssueTransaction
        .selfSigned(version, AddressScheme.current.chainId, sender, assetName, description, quantity, decimals, reissuable, script, fee, timestamp)
        .explicitGet()
}
