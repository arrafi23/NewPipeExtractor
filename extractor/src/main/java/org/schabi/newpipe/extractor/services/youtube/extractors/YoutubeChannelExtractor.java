package org.schabi.newpipe.extractor.services.youtube.extractors;

import com.grack.nanojson.JsonArray;
import com.grack.nanojson.JsonObject;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.channel.ChannelExtractor;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.exceptions.ContentNotSupportedException;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler;
import org.schabi.newpipe.extractor.localization.TimeAgoParser;
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeChannelLinkHandlerFactory;
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeParsingHelper;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamInfoItemsCollector;
import org.schabi.newpipe.extractor.utils.Utils;

import javax.annotation.Nonnull;
import java.io.IOException;

import static org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeParsingHelper.*;
import static org.schabi.newpipe.extractor.utils.JsonUtils.*;

/*
 * Created by Christian Schabesberger on 25.07.16.
 *
 * Copyright (C) Christian Schabesberger 2018 <chris.schabesberger@mailbox.org>
 * YoutubeChannelExtractor.java is part of NewPipe.
 *
 * NewPipe is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * NewPipe is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with NewPipe.  If not, see <http://www.gnu.org/licenses/>.
 */

@SuppressWarnings("WeakerAccess")
public class YoutubeChannelExtractor extends ChannelExtractor {
    private JsonObject initialData;
    private JsonObject videoTab;

    /**
     * Some channels have response redirects and the only way to reliably get the id is by saving it.
     *<p>
     * "Movies & Shows":
     * <pre>
     * UCuJcl0Ju-gPDoksRjK1ya-w ┐
     * UChBfWrfBXL9wS6tQtgjt_OQ ├ UClgRkhTL3_hImCAmdLfDE4g
     * UCok7UTQQEP1Rsctxiv3gwSQ ┘
     * </pre>
     */
    private String redirectedChannelId;

    public YoutubeChannelExtractor(StreamingService service, ListLinkHandler linkHandler) {
        super(service, linkHandler);
    }

    @Override
    public void onFetchPage(@Nonnull Downloader downloader) throws IOException, ExtractionException {
        String url = super.getUrl() + "/videos?pbj=1&view=0&flow=grid";
        JsonArray ajaxJson = null;

        int level = 0;
        while (level < 3) {
            final JsonArray jsonResponse = getJsonResponse(url, getExtractorLocalization());

            final JsonObject endpoint = jsonResponse.getObject(1, EMPTY_OBJECT)
                    .getObject("response", EMPTY_OBJECT).getArray("onResponseReceivedActions", EMPTY_ARRAY)
                    .getObject(0, EMPTY_OBJECT).getObject("navigateAction", EMPTY_OBJECT)
                    .getObject("endpoint", EMPTY_OBJECT);

            final String webPageType = endpoint
                    .getObject("commandMetadata", EMPTY_OBJECT)
                    .getObject("webCommandMetadata", EMPTY_OBJECT)
                    .getString("webPageType", EMPTY_STRING);

            final String browseId = endpoint
                    .getObject("browseEndpoint", EMPTY_OBJECT)
                    .getString("browseId", EMPTY_STRING);

            if (webPageType.equalsIgnoreCase("WEB_PAGE_TYPE_BROWSE") && !browseId.isEmpty()) {

                if (!browseId.startsWith("UC")) {
                    throw new ExtractionException("Redirected id is not pointing to a channel");
                }

                url = "https://www.youtube.com/channel/" + browseId + "/videos?pbj=1&view=0&flow=grid";
                redirectedChannelId = browseId;
                level++;
            } else {
                ajaxJson = jsonResponse;
                break;
            }
        }

        if (ajaxJson == null) {
            throw new ExtractionException("Could not fetch initial JSON data");
        }

        initialData = ajaxJson.getObject(1).getObject("response");
        YoutubeParsingHelper.defaultAlertsCheck(initialData);
    }


    @Override
    public String getNextPageUrl() throws ExtractionException {
        if (getVideoTab() == null) return "";
        return getNextPageUrlFrom(getVideoTab().getObject("content").getObject("sectionListRenderer")
                .getArray("contents").getObject(0).getObject("itemSectionRenderer")
                .getArray("contents").getObject(0).getObject("gridRenderer").getArray("continuations"));
    }

    @Nonnull
    @Override
    public String getUrl() throws ParsingException {
        try {
            return YoutubeChannelLinkHandlerFactory.getInstance().getUrl("channel/" + getId());
        } catch (ParsingException e) {
            return super.getUrl();
        }
    }

    @Nonnull
    @Override
    public String getId() throws ParsingException {
        final String channelId = initialData
            .getObject("header", EMPTY_OBJECT)
            .getObject("c4TabbedHeaderRenderer", EMPTY_OBJECT)
            .getString("channelId", EMPTY_STRING);

        if (!channelId.isEmpty()) {
            return channelId;
        } else if (redirectedChannelId != null && !redirectedChannelId.isEmpty()) {
            return redirectedChannelId;
        } else {
            throw new ParsingException("Could not get channel id");
        }
    }

    @Nonnull
    @Override
    public String getName() throws ParsingException {
        try {
            return initialData.getObject("header").getObject("c4TabbedHeaderRenderer").getString("title");
        } catch (Exception e) {
            throw new ParsingException("Could not get channel name", e);
        }
    }

    @Override
    public String getAvatarUrl() throws ParsingException {
        try {
            String url = initialData.getObject("header").getObject("c4TabbedHeaderRenderer").getObject("avatar")
                    .getArray("thumbnails").getObject(0).getString("url");

            return fixThumbnailUrl(url);
        } catch (Exception e) {
            throw new ParsingException("Could not get avatar", e);
        }
    }

    @Override
    public String getBannerUrl() throws ParsingException {
        try {
            String url = null;
            try {
                url = initialData.getObject("header").getObject("c4TabbedHeaderRenderer").getObject("banner")
                        .getArray("thumbnails").getObject(0).getString("url");
            } catch (Exception ignored) {}
            if (url == null || url.contains("s.ytimg.com") || url.contains("default_banner")) {
                return null;
            }

            return fixThumbnailUrl(url);
        } catch (Exception e) {
            throw new ParsingException("Could not get banner", e);
        }
    }

    @Override
    public String getFeedUrl() throws ParsingException {
        try {
            return YoutubeParsingHelper.getFeedUrlFrom(getId());
        } catch (Exception e) {
            throw new ParsingException("Could not get feed url", e);
        }
    }

    @Override
    public long getSubscriberCount() throws ParsingException {
        final JsonObject subscriberInfo = initialData.getObject("header").getObject("c4TabbedHeaderRenderer").getObject("subscriberCountText");
        if (subscriberInfo != null) {
            try {
                return Utils.mixedNumberWordToLong(getTextFromObject(subscriberInfo));
            } catch (NumberFormatException e) {
                throw new ParsingException("Could not get subscriber count", e);
            }
        } else {
            // If there's no subscribe button, the channel has the subscriber count disabled
            if (initialData.getObject("header").getObject("c4TabbedHeaderRenderer").getObject("subscribeButton") == null) {
                return -1;
            } else {
                return 0;
            }
        }
    }

    @Override
    public String getDescription() throws ParsingException {
        try {
            return initialData.getObject("metadata").getObject("channelMetadataRenderer").getString("description");
        } catch (Exception e) {
            throw new ParsingException("Could not get channel description", e);
        }
    }

    @Nonnull
    @Override
    public InfoItemsPage<StreamInfoItem> getInitialPage() throws ExtractionException {
        StreamInfoItemsCollector collector = new StreamInfoItemsCollector(getServiceId());

        if (getVideoTab() != null) {
            JsonArray videos = getVideoTab().getObject("content").getObject("sectionListRenderer").getArray("contents")
                    .getObject(0).getObject("itemSectionRenderer").getArray("contents").getObject(0)
                    .getObject("gridRenderer").getArray("items");
            collectStreamsFrom(collector, videos);
        }

        return new InfoItemsPage<>(collector, getNextPageUrl());
    }

    @Override
    public InfoItemsPage<StreamInfoItem> getPage(String pageUrl) throws IOException, ExtractionException {
        if (pageUrl == null || pageUrl.isEmpty()) {
            throw new ExtractionException(new IllegalArgumentException("Page url is empty or null"));
        }

        // Unfortunately, we have to fetch the page even if we are only getting next streams,
        // as they don't deliver enough information on their own (the channel name, for example).
        fetchPage();

        StreamInfoItemsCollector collector = new StreamInfoItemsCollector(getServiceId());
        final JsonArray ajaxJson = getJsonResponse(pageUrl, getExtractorLocalization());

        JsonObject sectionListContinuation = ajaxJson.getObject(1).getObject("response")
                .getObject("continuationContents").getObject("gridContinuation");

        collectStreamsFrom(collector, sectionListContinuation.getArray("items"));

        return new InfoItemsPage<>(collector, getNextPageUrlFrom(sectionListContinuation.getArray("continuations")));
    }


    private String getNextPageUrlFrom(JsonArray continuations) {
        if (continuations == null) return "";

        JsonObject nextContinuationData = continuations.getObject(0).getObject("nextContinuationData");
        String continuation = nextContinuationData.getString("continuation");
        String clickTrackingParams = nextContinuationData.getString("clickTrackingParams");
        return "https://www.youtube.com/browse_ajax?ctoken=" + continuation + "&continuation=" + continuation
                + "&itct=" + clickTrackingParams;
    }

    private void collectStreamsFrom(StreamInfoItemsCollector collector, JsonArray videos) throws ParsingException {
        collector.reset();

        final String uploaderName = getName();
        final String uploaderUrl = getUrl();
        final TimeAgoParser timeAgoParser = getTimeAgoParser();

        for (Object video : videos) {
            if (((JsonObject) video).getObject("gridVideoRenderer") != null) {
                collector.commit(new YoutubeStreamInfoItemExtractor(
                        ((JsonObject) video).getObject("gridVideoRenderer"), timeAgoParser) {
                    @Override
                    public String getUploaderName() {
                        return uploaderName;
                    }

                    @Override
                    public String getUploaderUrl() {
                        return uploaderUrl;
                    }
                });
            }
        }
    }

    private JsonObject getVideoTab() throws ParsingException {
        if (this.videoTab != null) return this.videoTab;

        JsonArray tabs = initialData.getObject("contents").getObject("twoColumnBrowseResultsRenderer")
                .getArray("tabs");
        JsonObject videoTab = null;

        for (Object tab : tabs) {
            if (((JsonObject) tab).getObject("tabRenderer") != null) {
                if (((JsonObject) tab).getObject("tabRenderer").getString("title").equals("Videos")) {
                    videoTab = ((JsonObject) tab).getObject("tabRenderer");
                    break;
                }
            }
        }

        if (videoTab == null) {
            throw new ContentNotSupportedException("This channel has no Videos tab");
        }

        try {
            if (getTextFromObject(videoTab.getObject("content").getObject("sectionListRenderer")
                    .getArray("contents").getObject(0).getObject("itemSectionRenderer")
                    .getArray("contents").getObject(0).getObject("messageRenderer")
                    .getObject("text")).equals("This channel has no videos."))
                return null;
        } catch (Exception ignored) {}

        this.videoTab = videoTab;
        return videoTab;
    }
}
