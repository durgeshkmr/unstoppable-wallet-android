package io.horizontalsystems.bankwallet.core.adapters

import io.horizontalsystems.bankwallet.core.AdapterState
import io.horizontalsystems.bankwallet.core.App
import io.horizontalsystems.bankwallet.core.ISendBitcoinAdapter
import io.horizontalsystems.bankwallet.core.UnsupportedAccountException
import io.horizontalsystems.bankwallet.entities.*
import io.horizontalsystems.bitcoincore.BitcoinCore
import io.horizontalsystems.bitcoincore.models.BalanceInfo
import io.horizontalsystems.bitcoincore.models.BlockInfo
import io.horizontalsystems.bitcoincore.models.TransactionInfo
import io.horizontalsystems.core.helpers.DateHelper
import io.horizontalsystems.litecoinkit.LitecoinKit
import io.horizontalsystems.litecoinkit.LitecoinKit.NetworkType
import io.reactivex.Single
import java.math.BigDecimal
import java.util.*

class LitecoinAdapter(override val kit: LitecoinKit, override val settings: BlockchainSetting?)
    : BitcoinBaseAdapter(kit, settings), LitecoinKit.Listener, ISendBitcoinAdapter {

    constructor(wallet: Wallet, settings: BlockchainSetting?, testMode: Boolean) : this(createKit(wallet, settings, testMode), settings)

    init {
        kit.listener = this
    }

    //
    // BitcoinBaseAdapter
    //

    override val satoshisInBitcoin: BigDecimal = BigDecimal.valueOf(Math.pow(10.0, decimal.toDouble()))

    override fun getReceiveAddressType(wallet: Wallet): String? {
        return settings?.derivation?.addressType
    }

    //
    // LitecoinKit Listener
    //

    override fun onBalanceUpdate(balance: BalanceInfo) {
        balanceUpdatedSubject.onNext(Unit)
    }

    override fun onLastBlockInfoUpdate(blockInfo: BlockInfo) {
        lastBlockUpdatedSubject.onNext(Unit)
    }

    override fun onKitStateUpdate(state: BitcoinCore.KitState) {
        when (state) {
            is BitcoinCore.KitState.Synced -> {
                if (this.state !is AdapterState.Synced) {
                    this.state = AdapterState.Synced
                }
            }
            is BitcoinCore.KitState.NotSynced -> {
                if (this.state !is AdapterState.NotSynced) {
                    this.state = AdapterState.NotSynced
                }
            }
            is BitcoinCore.KitState.Syncing -> {
                this.state.let { currentState ->
                    val newProgress = (state.progress * 100).toInt()
                    val newDate = kit.lastBlockInfo?.timestamp?.let { Date(it * 1000) }

                    if (currentState is AdapterState.Syncing && currentState.progress == newProgress) {
                        val currentDate = currentState.lastBlockDate
                        if (newDate != null && currentDate != null && DateHelper.isSameDay(newDate, currentDate)) {
                            return
                        }
                    }

                    this.state = AdapterState.Syncing(newProgress, newDate)
                }
            }
        }
    }

    override fun onTransactionsUpdate(inserted: List<TransactionInfo>, updated: List<TransactionInfo>) {
        val records = mutableListOf<TransactionRecord>()

        for (info in inserted) {
            records.add(transactionRecord(info))
        }

        for (info in updated) {
            records.add(transactionRecord(info))
        }

        transactionRecordsSubject.onNext(records)
    }

    override fun onTransactionsDelete(hashes: List<String>) {
        // ignored for now
    }

    override fun getTransactions(from: TransactionRecord?, limit: Int): Single<List<TransactionRecord>> {
        return kit.transactions(from?.uid, limit).map { it.map { tx -> transactionRecord(tx) } }
    }

    companion object {

        private fun getNetworkType(testMode: Boolean) =
                if (testMode) NetworkType.TestNet else NetworkType.MainNet

        private fun createKit(wallet: Wallet, settings: BlockchainSetting?, testMode: Boolean): LitecoinKit {
            val account = wallet.account
            val accountType = account.type
            val walletDerivation = settings?.derivation
            val syncMode = settings?.syncMode
            if (accountType is AccountType.Mnemonic && accountType.words.size == 12) {
                return LitecoinKit(context = App.instance,
                        words = accountType.words,
                        walletId = account.id,
                        syncMode = getSyncMode(syncMode),
                        networkType = getNetworkType(testMode),
                        confirmationsThreshold = defaultConfirmationsThreshold,
                        bip = getBip(walletDerivation))
            }

            throw UnsupportedAccountException()
        }

        fun clear(walletId: String, testMode: Boolean) {
            LitecoinKit.clear(App.instance, getNetworkType(testMode), walletId)
        }
    }
}