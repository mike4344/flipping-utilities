/*
 * Copyright (c) 2020, Belieal <https://github.com/Belieal>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */


package com.flippingutilities;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.Getter;
import net.runelite.api.events.GrandExchangeOfferChanged;

/**
 * Manages the history for an item. This class is responsible for figuring out how much profit a user made for
 * an item along with tracking how many items they bought since the last ge limit refresh and when the
 * next ge limit refresh for this an item will be.
 */
public class HistoryManager
{
	//contains the history for each slot so that when a new offer comes in for a slot, we can use the
	//slot history to figure out how many new items were bought/sold. When a offer with a state that is
	//complete (bought/sold/cancelled buy/cancelled sell) comes in, the history for that slot is removed
	//as the slot is now empty.
	private Map<Integer, List<OfferInfo>> slotHistory = new HashMap<>();

	//a list of standardizedOffers. A standardizedOffer is an offer with a currentQuantityInTrade that represents the
	//currentQuantityInTrade bought since the last offer. A regular offer just has info from an offerEvent, which gives
	//you the current currentQuantityInTrade bought/sold overall in the trade.
	@Getter
	private List<OfferInfo> standardizedOffers = new ArrayList<>();

	@Getter
	private Instant nextGeLimitRefresh;

	//the number of items bought since the last ge limit reset.
	@Getter
	private int itemsBoughtThisLimitWindow;

	/**
	 * This method takes in every new offer that comes and updates the standardized offer list along with
	 * other properties related to the history of an item such as how many items were bought since the last
	 * ge limit refresh and how when the ge limit will reset again. The standardized offer list is used to
	 * calculate profit for the item.
	 *
	 * @param newOffer the OfferInfo object created from the {@link GrandExchangeOfferChanged} event that
	 *                 onGrandExchangeOfferChanged (in FlippingPlugin) receives
	 */
	public void updateHistory(OfferInfo newOffer)
	{
		storeStandardizedOffer(newOffer);
		updateGeProperties();

	}

	/**
	 * Receives an offer, turns it into a standardized offer, and adds it to the standardized offer list.
	 * Standardizing an offer refers to making it reflect the currentQuantityInTrade bought/sold since last offer rather
	 * than the current amount bought/sold overall in the trade as is the default information in the OfferInfo
	 * constructed from a grandExchangeOfferChanged event.
	 *
	 * @param newOffer the OfferInfo object created from the {@link GrandExchangeOfferChanged} event that
	 *                 onGrandExchangeOfferChanged (in FlippingPlugin) receives. It is crucial to note that
	 *                 This OfferInfo object contains the current currentQuantityInTrade bought/sold for the trade currently.
	 */
	private void storeStandardizedOffer(OfferInfo newOffer)
	{
		int newOfferSlot = newOffer.getSlot();

		//if there are currently trades in progress in that slot
		if (slotHistory.containsKey(newOfferSlot))
		{

			List<OfferInfo> currentTradesForSlot = slotHistory.get(newOfferSlot);
			OfferInfo lastOffer = currentTradesForSlot.get(currentTradesForSlot.size() - 1);
			OfferInfo standardizedOffer = newOffer.standardizeOffer(lastOffer);
			standardizedOffers.add(standardizedOffer);
			currentTradesForSlot.add(newOffer);

			//if the offer is complete, clear the history for that slot.
			if (newOffer.isComplete())
			{
				slotHistory.remove(newOfferSlot);
			}
		}

		//its the first trade for that slot!
		else
		{
			//don't need to standardize as its currentQuantityInTrade represents the currentQuantityInTrade bought as its the first
			//trade in that slot.
			newOffer.setQuantitySinceLastOffer(newOffer.getCurrentQuantityInTrade());
			standardizedOffers.add(newOffer);

			//if the offer was a complete offer there's no need to add it to the slot history as a complete
			//offer means the slot history is over.
			if (!newOffer.isComplete())
			{
				slotHistory.put(newOfferSlot, new ArrayList<>(Arrays.asList(newOffer)));

			}
		}

	}

	/**
	 * Updates when the ge limit will refresh and how many items have been bought since the last
	 * ge limit refresh.
	 */
	private void updateGeProperties()
	{
		OfferInfo mostRecentOffer = standardizedOffers.get(standardizedOffers.size() - 1);
		if (!mostRecentOffer.isBuy())
		{
			return;
		}
		// when the time of the last offer (most recent offer) is greater than nextGeLimitRefresh,
		// you know the ge limits have refreshed. Since this is the first offer after the ge limits
		// have refreshed, the next refresh will be four hours after this offer's buy time.
		if (nextGeLimitRefresh == null || mostRecentOffer.getTime().compareTo(nextGeLimitRefresh) > 0)
		{
			nextGeLimitRefresh = mostRecentOffer.getTime().plus(4, ChronoUnit.HOURS);
			itemsBoughtThisLimitWindow = mostRecentOffer.getQuantitySinceLastOffer();
		}
		//if the last offer (most recent offer) is before the next ge limit refresh, add its currentQuantityInTrade to the
		//amount bought this limit window.
		else
		{
			itemsBoughtThisLimitWindow += mostRecentOffer.getQuantitySinceLastOffer();
		}

	}

	//TODO:
	// return a summary, not just the profit. A summary will include the profit, and the currentQuantityInTrade of buys/sells
	// and the individual prices (only if they are different)

	/**
	 * Calculates profit for a list of trades made with this item by counting the expenses and revenues
	 * accrued over these trades and figuring out the difference in value.
	 *
	 * @param tradeList The list of trades whose total profits will be calculated.
	 * @return profit
	 */
	public long currentProfit(List<OfferInfo> tradeList)
	{
		//return the value of the sell list - the value of the buy list. This is the profit.
		return getCashflow(tradeList, false) - getCashflow(tradeList, true);
	}

	/**
	 * This method finds the value of a list of offers. The boolean parameter determines if we calculate
	 * from buyList or sellList.
	 *
	 * @param tradeList  The list of standardized offers whose cashflow we want the value of.
	 * @param getExpense Options parameter that calculates, if true, the total expenses accrued
	 *                   and, if false, the total revenues accrued from the trades.
	 * @return Returns a long value based on the boolean parameter provided.
	 */
	public long getCashflow(List<OfferInfo> tradeList, boolean getExpense)
	{
		return getValueOfTrades(getSaleList(tradeList, getExpense), countItemsFlipped(tradeList));
	}

	/**
	 * Gets the currentQuantityInTrade of flipped items that has been done in a list of offers.
	 * The currentQuantityInTrade flipped is determined by the lowest of either number of items bought or sold.
	 *
	 * @param tradeList The list of items that the item count is based on
	 * @return An integer representing the total currentQuantityInTrade of items flipped in the list of offers
	 */
	public int countItemsFlipped(List<OfferInfo> tradeList)
	{
		int numBoughtItems = 0;
		int numSoldItems = 0;

		for (OfferInfo standardizedOffer : tradeList)
		{
			if (standardizedOffer.isBuy())
			{
				numBoughtItems += standardizedOffer.getQuantitySinceLastOffer();
			}
			else
			{
				numSoldItems += standardizedOffer.getQuantitySinceLastOffer();
			}
		}

		return Math.min(numBoughtItems, numSoldItems);
	}

	/**
	 * Gets the list of trades of either buy or sell states from a list of trades.
	 *
	 * @param tradeList The list of trades that will be checked.
	 * @param buyState  true will return offers that have been bought and false will return offers that have been sold.
	 * @return A list of items either sold or bought over a period of time.
	 */
	private ArrayList<OfferInfo> getSaleList(List<OfferInfo> tradeList, boolean buyState)
	{
		ArrayList<OfferInfo> results = new ArrayList<>();

		for (OfferInfo standardizedOffer : tradeList)
		{
			if (standardizedOffer.isBuy() == buyState)
			{
				results.add(standardizedOffer);
			}
		}

		return results;
	}

	/**
	 * Calculates the amount of money spent on either a buy or sell list, up to the amount of items
	 * specified by the limit.
	 *
	 * @param tradeList a buy or a sell list
	 * @param itemLimit the amount of items to calculate the value up until. This is for the case
	 *                  when a user has an unequal amount of buys/sells in which case you want to return the
	 *                  profit the items only up until the buys and sells are equal.
	 * @return the amount of money spent on the offer list, up to the amount of items specified by the
	 * limit
	 */
	private long getValueOfTrades(List<OfferInfo> tradeList, int itemLimit)
	{
		int itemsSeen = 0;
		long moneySpent = 0;


		for (OfferInfo offer : tradeList)
		{
			if (itemsSeen + offer.getQuantitySinceLastOffer() >= itemLimit)
			{
				moneySpent += (itemLimit - itemsSeen) * offer.getPrice();
				break;
			}
			else
			{
				moneySpent += offer.getQuantitySinceLastOffer() * offer.getPrice();
				itemsSeen += offer.getQuantitySinceLastOffer();
			}
		}

		return moneySpent;
	}

	public void sortHistoryByLatestActivity()
	{
		if (standardizedOffers.size() > 0)
		{
			standardizedOffers.sort(Comparator.comparing(OfferInfo::getTime));
		}
	}

	/**
	 * Returns the history of the item that were traded between earliestTime and now.
	 *
	 * @param earliestTime the earliest time that trades from the trade history are added to the resulting list.
	 * @return A list of offers that were within the interval of earliestTime and now.
	 */
	public ArrayList<OfferInfo> getIntervalsHistory(Instant earliestTime)
	{
		ArrayList<OfferInfo> result = new ArrayList<>();

		for (OfferInfo offer : standardizedOffers)
		{
			if (offer.getTime().isAfter(earliestTime))
			{
				result.add(offer);
			}
		}

		return result;
	}

	/**
	 * This is to prevent old values from remaining for items that a user has bought and whose
	 * refresh times have already passed. If the user buys the item again, the values will be up to date,
	 * so this method wouldn't be needed, but there is no guarantee the user buys the item again after the
	 * limit refreshes. This method should be called periodically to ensure no old values will remain.
	 */
	public void validateGeProperties()
	{

		if (nextGeLimitRefresh == null)
		{
			return;
		}

		if (Instant.now().compareTo(nextGeLimitRefresh) >= 0)

		{
			nextGeLimitRefresh = null;
			itemsBoughtThisLimitWindow = 0;
		}
	}


	public ArrayList<Flip> getFlips(Instant earliestTime)
	{
		ArrayList<OfferInfo> intervalHistory = getIntervalsHistory(earliestTime);

		List<OfferInfo> buyMarginChecks = new ArrayList<>();
		List<OfferInfo> sellMarginChecks = new ArrayList<>();
		List<OfferInfo> nonMarginCheckBuys = new ArrayList<>();
		List<OfferInfo> nonMarginCheckSells = new ArrayList<>();

		ArrayList<Flip> flips = new ArrayList<>();

		for (OfferInfo offer : intervalHistory)
		{
			if (offer.isMarginCheck())
			{
				if (offer.isBuy())
				{
					buyMarginChecks.add(offer);
				}
				else
				{
					sellMarginChecks.add(offer);
				}
			}

			else if (!offer.isMarginCheck() && offer.isComplete())
			{
				if (offer.isBuy())
				{
					nonMarginCheckBuys.add(offer);
				}
				else
				{
					nonMarginCheckSells.add(offer);
				}
			}
		}

		flips.addAll(combineToFlips(buyMarginChecks, sellMarginChecks));
		flips.addAll(combineToFlips(nonMarginCheckBuys, nonMarginCheckSells));
		flips.sort(Comparator.comparing(Flip::getTime));
		Collections.reverse(flips);
		return flips;
	}

	/**
	 * This method creates Flips from consolidated buy and sell offers. Consolidated buy and sells offers represent the
	 * entire trade which could have been made up by many offers. As such it has the currentQuantityInTrade of the entire trade (the
	 * sum of all the individual offers' quantities that made up the trade).
	 *
	 * @param buys  consolidated buys which each represent an entire trade
	 * @param sells consolidated sells which each represent an entire trade
	 * @return flips that represent a buy followed by a sell.
	 */
	private ArrayList<Flip> combineToFlips(List<OfferInfo> buys, List<OfferInfo> sells)
	{
		ArrayList<Flip> flips = new ArrayList<>();
		int buyListPointer = 0;
		int sellListPointer = 0;
		while (buyListPointer < buys.size() && sellListPointer < sells.size())
		{
			OfferInfo buy = buys.get(buyListPointer);
			OfferInfo sell = sells.get(sellListPointer);

			if (sell.getCurrentQuantityInTrade() >= buy.getCurrentQuantityInTrade())
			{
				sell.setCurrentQuantityInTrade(sell.getCurrentQuantityInTrade() - buy.getCurrentQuantityInTrade());
				buyListPointer++;
				flips.add(new Flip(buy.getPrice(), sell.getPrice(), buy.getCurrentQuantityInTrade(), sell.getTime(), false));
			}

			else
			{
				buy.setCurrentQuantityInTrade(buy.getCurrentQuantityInTrade() - sell.getCurrentQuantityInTrade());
				sellListPointer++;
				flips.add(new Flip(buy.getPrice(), sell.getPrice(), sell.getCurrentQuantityInTrade(), sell.getTime(), false));
			}
		}
		return flips;

	}
}
