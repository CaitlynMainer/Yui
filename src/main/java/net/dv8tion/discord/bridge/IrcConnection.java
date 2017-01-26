/**
 *     Copyright 2015-2016 Austin Keener
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.dv8tion.discord.bridge;

import net.dv8tion.discord.Yuri;
import net.dv8tion.discord.bridge.endpoint.EndPoint;
import net.dv8tion.discord.bridge.endpoint.EndPointInfo;
import net.dv8tion.discord.bridge.endpoint.EndPointManager;
import net.dv8tion.discord.bridge.endpoint.EndPointMessage;
import net.dv8tion.discord.bridge.endpoint.messages.DiscordEndPointMessage;
import net.dv8tion.discord.bridge.endpoint.messages.IrcActionEndPointMessage;
import net.dv8tion.discord.bridge.endpoint.messages.IrcEndPointMessage;
import net.dv8tion.discord.util.AntiPing;
import net.dv8tion.discord.util.TimedHashMap;
import net.dv8tion.discord.util.makeTiny;
import net.dv8tion.jda.entities.Message;
import net.dv8tion.jda.entities.MessageEmbed;
import net.dv8tion.jda.entities.User;
import net.dv8tion.jda.events.Event;
import net.dv8tion.jda.events.message.guild.GenericGuildMessageEvent;
import net.dv8tion.jda.events.message.guild.GuildMessageDeleteEvent;
import net.dv8tion.jda.events.message.guild.GuildMessageEmbedEvent;
import net.dv8tion.jda.hooks.EventListener;
import net.dv8tion.jda.managers.ChannelManager;
import org.pircbotx.Channel;
import org.pircbotx.Configuration.Builder;
import org.pircbotx.PircBotX;
import org.pircbotx.exception.IrcException;
import org.pircbotx.hooks.ListenerAdapter;
import org.pircbotx.hooks.events.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class IrcConnection extends ListenerAdapter<PircBotX> implements EventListener
{
    public static final int MESSAGE_DELAY_AMOUNT = 250;

    private String identifier;
    private Thread botThread;
    private PircBotX bot;


    public IrcConnection(IrcConnectInfo info)
    {
        identifier = info.getIdentifier();
        Builder<PircBotX> builder = info.getIrcConfigBuilder();
        builder.addListener(this);
        builder.setMessageDelay(MESSAGE_DELAY_AMOUNT);
        builder.setAutoReconnect(true);
        builder.setLogin(builder.getName());
        bot = new PircBotX(builder.buildConfiguration());
        this.open();

    }

    public void open()
    {
        if (botThread != null)
            throw new IllegalStateException("We tried to create another bot thread before killing the current one!");

        botThread = new Thread()
        {
            public void run()
            {
                try
                {
                    bot.startBot();
                }
                catch (IOException | IrcException e)
                {
                    System.err.println("Yeah.. idk. Sorry");
                    e.printStackTrace();
                }
            }
        };
        botThread.start();
    }

    public void close(String reason)
    {
        //TODO: Cleanup the EndPoints of this connection in EndPointManager.
        bot.sendIRC().quitServer(reason);
    }

    public String getIdentifier()
    {
        return identifier;
    }

    public PircBotX getIrcBot()
    {
        return bot;
    }
    // -----  Events -----

    // -- IRC --
    @Override
    public void onTopic(TopicEvent<PircBotX> event) {
        //If this returns null, then this EndPoint isn't part of a bridge.
        EndPoint endPoint = BridgeManager.getInstance().getOtherEndPoint(EndPointInfo.createFromIrcChannel(identifier, event.getChannel()));
        if (endPoint != null){
            ChannelManager chanMan = new ChannelManager(Yuri.getAPI().getTextChannelById(endPoint.toEndPointInfo().getChannelId()));
            chanMan.setTopic(event.getTopic());
            chanMan.update();
        }
    }

    @Override
    public void onMessage(MessageEvent<PircBotX> event)
    {
        Boolean checkStatus = false;
        //Specific to the the Imaginescape IRC/Discord channel. Dumb minecraft server spits out an empty message that is really annoying.
        if (event.getUser().getNick().equals("IServer") && event.getMessage().equals("[Server]"))
            return;

        if (event.getMessage().startsWith("@status")) {
            checkStatus = true;
        }

        //If this returns null, then this EndPoint isn't part of a bridge.
        EndPoint endPoint = BridgeManager.getInstance().getOtherEndPoint(EndPointInfo.createFromIrcChannel(identifier, event.getChannel()));
        if (endPoint != null)
        {
            EndPointMessage message = new IrcEndPointMessage(event);
            Pattern pattern = Pattern.compile("@[^\\s]+\\b");
            Matcher matcher = pattern.matcher(message.getMessage());
            while(matcher.find())
            {
                for (User user : Yuri.getAPI().getUsers()) {
                    if (user.getUsername().equalsIgnoreCase(matcher.group(0).replace("@",""))) {
                        if (checkStatus) {
                            event.respond(user.getUsername() + " is currently " + user.getOnlineStatus());
                        }
                        message.setMessage(message.getMessage().replace(matcher.group(0).replace("@",""), user.getAsMention()).replace("@<","<"));
                    }
                }
            }
            endPoint.sendMessage(message);
        }
    }

    @Override
    public void onAction(ActionEvent<PircBotX> event) throws Exception
    {
        //If this returns null, then this EndPoint isn't part of a bridge.
        EndPoint endPoint = BridgeManager.getInstance().getOtherEndPoint(EndPointInfo.createFromIrcChannel(identifier, event.getChannel()));
        if (endPoint != null)
        {
            EndPointMessage message = new IrcActionEndPointMessage(event);
            Pattern pattern = Pattern.compile("@[^\\s]+\\b");
            Matcher matcher = pattern.matcher(message.getMessage());
            while(matcher.find())
            {
                for (User user : Yuri.getAPI().getUsers()) {
                    if (user.getUsername().equalsIgnoreCase(matcher.group(0).replace("@",""))) {
                        message.setMessage(message.getMessage().replace(matcher.group(0).replace("@",""), user.getAsMention()).replace("@<","<"));
                    }
                }
            }
            endPoint.sendMessage(message);
        }
    }

    @Override
    public void onConnect(ConnectEvent<PircBotX> event)
    {

    }

    @Override
    public void onQuit (QuitEvent<PircBotX> event) {

        String nick = event.getUser().getNick();
        for (String channelName : Yuri.channelNicks.keySet()) {
            if (Yuri.channelNicks.get(channelName).contains(nick)) {
                EndPoint endPoint = BridgeManager.getInstance().getOtherEndPoint(EndPointInfo.createFromIrcChannel(identifier, getChannel(channelName)));
                endPoint.sendMessage(event.getUser().getNick() + " has quit IRC (" + event.getReason() + ")" );
            }
            Yuri.channelNicks.get(channelName).remove(nick);
        }
        updateNickList();
    }

    @Override
    public void onPart (PartEvent<PircBotX> event) {
        //if (messages.containsValue(event.getUser().getNick())) {

        EndPoint endPoint = BridgeManager.getInstance().getOtherEndPoint(EndPointInfo.createFromIrcChannel(identifier, event.getChannel()));
        endPoint.sendMessage(event.getUser().getNick() + " has left " + event.getChannel().getName() + " on IRC (" + event.getReason() + ")");
        //}
        updateNickList(event.getChannel());
    }

    @Override
    public void onJoin(JoinEvent<PircBotX> event)
    {
        if (event.getBot().getUserBot().equals(event.getUser())) {
            System.out.println("Joined: " + event.getChannel().getName());
            EndPointManager.getInstance().createEndPoint(EndPointInfo.createFromIrcChannel(identifier, event.getChannel()));
        } else {
            updateNickList(event.getChannel());
            EndPoint endPoint = BridgeManager.getInstance().getOtherEndPoint(EndPointInfo.createFromIrcChannel(identifier, event.getChannel()));
            if (endPoint != null)
            {
                endPoint.sendMessage(event.getUser().getNick() + " has joined " + event.getChannel().getName() + " on IRC");
            }
        }
    }

    private String removeUrl(String commentstr)
    {
        String urlPattern = "((https?|ftp|gopher|telnet|file|Unsure|http):((//)|(\\\\))+[\\w\\d:#@%/;$()~_?\\+-=\\\\\\.&]*)";
        Pattern p = Pattern.compile(urlPattern,Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(commentstr);
        int i = 0;
        while (m.find()) {
            commentstr = commentstr.replaceAll(m.group(i),"").trim();
            i++;
        }
        return commentstr;
    }

    public String addSpace(String in) {
        if (in.length() > 0) {
            return in + " ";
        } else {
            return in;
        }
    }

    // -- Discord --

    @Override
    public void onEvent(Event event)
    {
        //We only deal with TextChannel Message events
        if (!(event instanceof GenericGuildMessageEvent))
            return;

        //Don't care about deleted messages or embeds.
        if (event instanceof GuildMessageDeleteEvent /*|| event instanceof GuildMessageEmbedEvent*/)
            return;

        GenericGuildMessageEvent e = (GenericGuildMessageEvent) event;

        //Basically: If we are the ones that sent the message, don't send it to IRC.
        if (event.getJDA().getSelfInfo().getId().equals(e.getAuthor().getId()))
            return;

        //If this returns null, then this EndPoint isn't part of a bridge.
        EndPoint endPoint = BridgeManager.getInstance().getOtherEndPoint(EndPointInfo.createFromDiscordChannel(e.getChannel()));
        if (endPoint != null)
        {
            EndPointMessage message = new DiscordEndPointMessage(e);
            String parsedMessage = "";
            String nick;
            String tinyURL = "";
            if (!e.getMessage().getAttachments().isEmpty()) {
                for (Message.Attachment attach : e.getMessage().getAttachments()) {
                    if (message.getSenderNick() != null) {
                        nick = message.getSenderNick();
                    } else {
                        nick = message.getSenderName();
                    }
                    nick = AntiPing.antiPing(nick);
                    tinyURL = makeTiny.getTinyURL(attach.getUrl());
                    parsedMessage += "<"+nick+"> " + addSpace(removeUrl(message.getMessage())) + tinyURL;
                }
                parsedMessage.replace(tinyURL, "");
                endPoint.sendMessage(parsedMessage.toString());
            } else {
                message = new DiscordEndPointMessage(e);
                endPoint.sendMessage(message);
            }
        }
    }
    public void updateNickList() {
        if (!this.getIrcBot().isConnected()) {
            return;
        }
        for (Channel channel : this.getIrcBot().getUserChannelDao().getAllChannels()) {
            this.updateNickList(channel);
        }
    }

    public void updateNickList(Channel channel) {
        if (!this.getIrcBot().isConnected()) {
            return;
        }
        // Build current list of names in channel
        ArrayList<String> users = new ArrayList<>();
        for (org.pircbotx.User user : channel.getUsers()) {
            //plugin.logDebug("N: " + user.getNick());
            users.add(user.getNick());
        }
        try {
            Yuri.wl.tryLock(10, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            return;
        }
        try {
            String channelName = channel.getName();
            if (Yuri.channelNicks.containsKey(channelName)) {
                for (String name : Yuri.channelNicks.get(channelName)) {
                    //plugin.logDebug("O: " + name);
                    if (!users.contains(name)) {
                        //old code
                    }
                }
                Yuri.channelNicks.remove(channelName);
            }
            Yuri.channelNicks.put(channelName, users);
        } finally {
            Yuri.wl.unlock();
        }
    }
    public Channel getChannel(String channelName) {
        Channel channel = null;
        for (Channel c : this.getIrcBot().getUserChannelDao().getAllChannels()) {
            if (c.getName().equalsIgnoreCase(channelName)) {
                return c;
            }
        }
        return channel;
    }
}
