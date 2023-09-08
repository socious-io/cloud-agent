package io.iohk.atala.agent.walletapi.memory

import io.iohk.atala.agent.walletapi.storage.DIDSecret
import io.iohk.atala.agent.walletapi.storage.DIDSecretStorage
import io.iohk.atala.mercury.model.DidId
import io.iohk.atala.shared.models.WalletAccessContext
import io.iohk.atala.shared.models.WalletId
import zio.*

class DIDSecretStorageInMemory(walletRefs: Ref[Map[WalletId, Ref[Map[String, DIDSecret]]]]) extends DIDSecretStorage {

  private def walletStoreRef: URIO[WalletAccessContext, Ref[Map[String, DIDSecret]]] =
    for {
      walletId <- ZIO.serviceWith[WalletAccessContext](_.walletId)
      refs <- walletRefs.get
      maybeWalletRef = refs.get(walletId)
      walletRef <- maybeWalletRef
        .fold {
          for {
            ref <- Ref.make(Map.empty[String, DIDSecret])
            _ <- walletRefs.set(refs.updated(walletId, ref))
          } yield ref
        }(ZIO.succeed)
    } yield walletRef

  private def constructKey(did: DidId, keyId: String, schemaId: String): String = s"${did}_${keyId}_${schemaId}"

  override def getKey(did: DidId, keyId: String, schemaId: String): RIO[WalletAccessContext, Option[DIDSecret]] =
    walletStoreRef.flatMap { storeRef =>
      storeRef.get.map(_.get(constructKey(did, keyId, schemaId)))
    }

  override def insertKey(did: DidId, keyId: String, didSecret: DIDSecret): RIO[WalletAccessContext, Int] =
    walletStoreRef.flatMap(storeRef =>
      storeRef.modify { store =>
        val key = constructKey(did, keyId, didSecret.schemaId)
        if (store.contains(key)) {
          (
            ZIO.fail(Exception(s"Unique constraint violation, key $key already exist.")),
            store
          ) // Already exists, so we're effectively updating it
        } else {
          (ZIO.succeed(1), store.updated(key, didSecret))
        }
      }.flatten
    )
}

object DIDSecretStorageInMemory {
  val layer: ULayer[DIDSecretStorage] =
    ZLayer.fromZIO(
      Ref
        .make(Map.empty[WalletId, Ref[Map[String, DIDSecret]]])
        .map(DIDSecretStorageInMemory(_))
    )

}
