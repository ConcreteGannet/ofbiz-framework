/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.ofbiz.accounting.accounting

import java.sql.Timestamp
import org.apache.ofbiz.base.util.UtilDateTime
import org.apache.ofbiz.entity.transaction.TransactionUtil
import org.apache.ofbiz.service.ServiceUtil
import org.apache.ofbiz.testtools.JunitJupiterTest
import org.apache.ofbiz.testtools.JupiterTestHelper
import org.junit.jupiter.api.Test

@JunitJupiterTest
class SalesShipmentCostingTests implements JupiterTestHelper {
    @Test
    void testInventoryCost() {
        checkSingleItemCost('COGS_INV_COST', 18.00G)
    }

    @Test
    void testAverageCost() {
        checkSingleItemCost('COGS_AVG_COST', 26.00G)
    }

    @Test
    void testFifo() {
        checkLayers('COGS_FIFO', 0.0G, 4.0G, 58.00G)
    }

    @Test
    void testLifo() {
        checkLayers('COGS_LIFO', 4.0G, 0.0G, 74.00G)
    }

    @Test
    void testInsufficientStockRollsBack() {
        checkFailure('COGS_FIFO', 6.0G)
    }

    @Test
    void testUnsupportedMethod() {
        checkFailure('COGS_UNSUPPORTED', 2.0G)
    }

    @Test
    void testDisabledAccounting() {
        boolean began = TransactionUtil.begin()
        assert began
        try {
            Map fixture = createFixture('COGS_FIFO', 2.0G)
            def preference = from('PartyAcctgPreference').where('partyId', fixture.id).queryOne()
            preference.enableAccounting = 'N'
            preference.store()
            Map result = issue(fixture.issuanceId)
            assert ServiceUtil.isSuccess(result): result
            assert !result.acctgTransId
            assert quantity(fixture.inventoryId) == 5.0G
            assert from('InventoryItemDetail').where('inventoryItemId', fixture.inventoryId).queryList().size() == 1
            assert from('AcctgTrans').where('inventoryItemId', fixture.inventoryId).queryList().isEmpty()
        } finally {
            TransactionUtil.rollback(began, 'Discard sales shipment costing fixture', null)
        }
    }

    private void checkFailure(String method, BigDecimal requested) {
        boolean began = TransactionUtil.begin()
        assert began
        Map fixture
        try {
            if (method == 'COGS_UNSUPPORTED') {
                delegator.create('Enumeration', [enumId: method, enumTypeId: 'COGS_METHODS', description: 'Test unsupported method'])
            }
            fixture = createFixture(method, requested)
            TransactionUtil.commit(began)
        } catch (Throwable t) {
            TransactionUtil.rollback(began, 'Fixture setup failed', t)
            throw t
        }
        try {
            Map result = issue(fixture.issuanceId as String)
            assert ServiceUtil.isError(result): result
            assert !result.acctgTransId
            assert quantity(fixture.inventoryId as String) == 5.0G
            def details = from('InventoryItemDetail').where('inventoryItemId', fixture.inventoryId).queryList()
            assert details.size() == 1
            assert details[0].accountingQuantityDiff == 5.0G
            assert from('AcctgTrans').where('inventoryItemId', fixture.inventoryId).queryList().isEmpty()
        } finally {
            delegator.removeByAnd('ItemIssuance', [itemIssuanceId: fixture.issuanceId])
            delegator.removeByAnd('InventoryItemDetail', [inventoryItemId: fixture.inventoryId])
            delegator.removeByAnd('InventoryItem', [inventoryItemId: fixture.inventoryId])
            delegator.removeByAnd('Facility', [facilityId: fixture.id])
            delegator.removeByAnd('ProductKeyword', [productId: fixture.id])
            delegator.removeByAnd('Product', [productId: fixture.id])
            delegator.removeByAnd('GlAccountTypeDefault', [organizationPartyId: fixture.id])
            delegator.removeByAnd('GlAccountOrganization', [organizationPartyId: fixture.id])
            delegator.removeByAnd('CustomTimePeriod', [organizationPartyId: fixture.id])
            delegator.removeByAnd('PartyAcctgPreference', [partyId: fixture.id])
            delegator.removeByAnd('PartyRole', [partyId: fixture.id, roleTypeId: 'INTERNAL_ORGANIZATIO'])
            delegator.removeByAnd('Party', [partyId: fixture.id])
            if (method == 'COGS_UNSUPPORTED') {
                delegator.removeByAnd('Enumeration', [enumId: method])
            }
        }
    }

    private void checkLayers(String method, BigDecimal oldRemaining, BigDecimal newRemaining, BigDecimal amount) {
        boolean began = TransactionUtil.begin()
        assert began
        try {
            Map fixture = createFixture(method, 6.0G)
            String newer = addInventory(fixture.id, 5.0G, 13.00G, '2002-01-01 00:00:00')
            Map result = issue(fixture.issuanceId)
            assert ServiceUtil.isSuccess(result): result
            assert result.acctgTransId
            assert quantity(fixture.inventoryId) == oldRemaining
            assert quantity(newer) == newRemaining
            assert quantity(fixture.inventoryId) + quantity(newer) == 4.0G
            assert deduction(fixture.inventoryId) == oldRemaining - 5.0G
            assert deduction(newer) == newRemaining - 5.0G
            def entries = from('AcctgTransEntry').where('acctgTransId', result.acctgTransId).queryList()
            assert entries.size() == 3
            assert entries.find { it.debitCreditFlag == 'D' && it.glAccountTypeId == 'COGS_ACCOUNT' && it.origAmount == amount }
            assert entries.find { it.debitCreditFlag == 'C' && it.glAccountTypeId == 'INVENTORY_ACCOUNT' &&
                    it.inventoryItemId == fixture.inventoryId && it.origAmount == (5.0G - oldRemaining) * 9.00G }
            assert entries.find { it.debitCreditFlag == 'C' && it.glAccountTypeId == 'INVENTORY_ACCOUNT' &&
                    it.inventoryItemId == newer && it.origAmount == (5.0G - newRemaining) * 13.00G }
        } finally {
            TransactionUtil.rollback(began, 'Discard sales shipment costing fixture', null)
        }
    }

    private BigDecimal quantity(String inventoryId) {
        from('InventoryItem').where('inventoryItemId', inventoryId).queryOne().accountingQuantityTotal as BigDecimal
    }

    private BigDecimal deduction(String inventoryId) {
        def details = from('InventoryItemDetail').where('inventoryItemId', inventoryId).queryList()
        assert details.size() == 2
        def negative = details.findAll { it.accountingQuantityDiff < 0.0G }
        assert negative.size() == 1
        negative[0].accountingQuantityDiff as BigDecimal
    }

    private Map issue(String issuanceId) {
        dispatcher.runSync('createAcctgTransForSalesShipmentIssuance', [itemIssuanceId: issuanceId, userLogin: userLogin])
    }

    private String addInventory(String id, BigDecimal qty, BigDecimal cost, String date) {
        String inventoryId = delegator.getNextSeqId('InventoryItem')
        delegator.create('InventoryItem', [inventoryItemId: inventoryId, inventoryItemTypeId: 'NON_SERIAL_INV_ITEM',
                productId: id, facilityId: id, ownerPartyId: id, unitCost: cost, currencyUomId: 'USD',
                datetimeReceived: Timestamp.valueOf(date)])
        Map initial = dispatcher.runSync('createInventoryItemDetail', [inventoryItemId: inventoryId,
                accountingQuantityDiff: qty, userLogin: userLogin])
        assert ServiceUtil.isSuccess(initial): initial
        inventoryId
    }

    private Map createFixture(String method, BigDecimal qty) {
        String id = delegator.getNextSeqId('Party')
        String issuanceId = delegator.getNextSeqId('ItemIssuance')
        delegator.create('Party', [partyId: id, partyTypeId: 'PARTY_GROUP'])
        delegator.create('PartyRole', [partyId: id, roleTypeId: 'INTERNAL_ORGANIZATIO'])
        delegator.create('PartyAcctgPreference', [partyId: id, cogsMethodId: method,
                enableAccounting: 'Y', baseCurrencyUomId: 'USD'])
        delegator.create('CustomTimePeriod', [customTimePeriodId: delegator.getNextSeqId('CustomTimePeriod'),
                organizationPartyId: id, periodTypeId: 'FISCAL_YEAR', fromDate: java.sql.Date.valueOf('2000-01-01'),
                thruDate: java.sql.Date.valueOf('2099-12-31'), isClosed: 'N'])
        ['INVENTORY_ACCOUNT': '140000', 'COGS_ACCOUNT': '500000'].each { type, account ->
            delegator.create('GlAccountOrganization', [organizationPartyId: id, glAccountId: account,
                    fromDate: Timestamp.valueOf('2000-01-01 00:00:00')])
            delegator.create('GlAccountTypeDefault', [organizationPartyId: id, glAccountTypeId: type, glAccountId: account])
        }
        delegator.create('Product', [productId: id, productTypeId: 'FINISHED_GOOD', internalName: id])
        delegator.create('Facility', [facilityId: id, facilityTypeId: 'WAREHOUSE', ownerPartyId: id])
        String inventoryId = addInventory(id, 5.0G, 9.00G, '2001-01-01 00:00:00')
        delegator.create('ItemIssuance', [itemIssuanceId: issuanceId, inventoryItemId: inventoryId,
                issuedDateTime: UtilDateTime.nowTimestamp(), quantity: qty])
        [id: id, inventoryId: inventoryId, issuanceId: issuanceId]
    }

    private void checkSingleItemCost(String method, BigDecimal expectedAmount) {
        boolean began = TransactionUtil.begin()
        assert began
        try {
            String id = delegator.getNextSeqId('Party')
            String inventoryId = delegator.getNextSeqId('InventoryItem')
            String issuanceId = delegator.getNextSeqId('ItemIssuance')
            delegator.create('Party', [partyId: id, partyTypeId: 'PARTY_GROUP'])
            delegator.create('PartyRole', [partyId: id, roleTypeId: 'INTERNAL_ORGANIZATIO'])
            delegator.create('PartyAcctgPreference', [partyId: id, cogsMethodId: method,
                    enableAccounting: 'Y', baseCurrencyUomId: 'USD'])
            delegator.create('CustomTimePeriod', [customTimePeriodId: delegator.getNextSeqId('CustomTimePeriod'),
                    organizationPartyId: id, periodTypeId: 'FISCAL_YEAR', fromDate: java.sql.Date.valueOf('2000-01-01'),
                    thruDate: java.sql.Date.valueOf('2099-12-31'), isClosed: 'N'])
            ['INVENTORY_ACCOUNT': '140000', 'COGS_ACCOUNT': '500000'].each { type, account ->
                delegator.create('GlAccountOrganization', [organizationPartyId: id, glAccountId: account,
                        fromDate: Timestamp.valueOf('2000-01-01 00:00:00')])
                delegator.create('GlAccountTypeDefault', [organizationPartyId: id, glAccountTypeId: type, glAccountId: account])
            }
            delegator.create('Product', [productId: id, productTypeId: 'FINISHED_GOOD', internalName: id])
            delegator.create('Facility', [facilityId: id, facilityTypeId: 'WAREHOUSE', ownerPartyId: id])
            delegator.create('InventoryItem', [inventoryItemId: inventoryId, inventoryItemTypeId: 'NON_SERIAL_INV_ITEM',
                    productId: id, facilityId: id, ownerPartyId: id, unitCost: 9.00G, currencyUomId: 'USD'])
            Map initial = dispatcher.runSync('createInventoryItemDetail', [inventoryItemId: inventoryId,
                    accountingQuantityDiff: 10.0G, userLogin: userLogin])
            assert ServiceUtil.isSuccess(initial): initial
            delegator.create('ProductAverageCost', [productAverageCostTypeId: 'SIMPLE_AVG_COST', organizationPartyId: id,
                    productId: id, facilityId: id, fromDate: Timestamp.valueOf('2001-01-01 00:00:00'), averageCost: 13.00G])
            delegator.create('ItemIssuance', [itemIssuanceId: issuanceId, inventoryItemId: inventoryId,
                    issuedDateTime: UtilDateTime.nowTimestamp(), quantity: 2.0G])

            Map result = dispatcher.runSync('createAcctgTransForSalesShipmentIssuance', [itemIssuanceId: issuanceId, userLogin: userLogin])
            assert ServiceUtil.isSuccess(result): result
            assert result.acctgTransId
            assert from('InventoryItem').where('inventoryItemId', inventoryId).queryOne().accountingQuantityTotal == 8.0G
            def details = from('InventoryItemDetail').where('inventoryItemId', inventoryId).queryList()
            assert details.size() == 2
            assert details.findAll { it.accountingQuantityDiff == -2.0G }.size() == 1
            def entries = from('AcctgTransEntry').where('acctgTransId', result.acctgTransId).queryList()
            assert entries.size() == 2
            assert entries.find { it.debitCreditFlag == 'C' && it.glAccountTypeId == 'INVENTORY_ACCOUNT' &&
                    it.inventoryItemId == inventoryId && it.origAmount == expectedAmount }
            assert entries.find { it.debitCreditFlag == 'D' && it.glAccountTypeId == 'COGS_ACCOUNT' && it.origAmount == expectedAmount }
        } finally {
            TransactionUtil.rollback(began, 'Discard sales shipment costing fixture', null)
        }
    }
}