package com.k10.smsbridge.data

import com.k10.smsbridge.sms.ParsedSms

fun ParsedSms.toEntity() = TransactionEntity(
    uniqueLocalId = uniqueLocalId,
    duplicateKey = duplicateKey,
    payerName = payerName,
    amountMinor = amountMinor,
    transactionDate = transactionDate.toString(),
    smsReceivedTimestamp = smsReceivedTimestamp,
    accountLast4 = accountLast4,
    paymentMethod = paymentMethod,
    senderId = senderId,
    rawEligibleSms = rawEligibleSms
)
