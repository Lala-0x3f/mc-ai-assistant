package com.mcaiassistant.mcaiassistant;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 聊天记录管理器
 * 负责管理聊天历史记录，提供上下文支持
 */
public class ChatHistoryManager {
    
    private final LinkedList<ChatMessage> chatHistory;
    private final ReadWriteLock lock;
    private static final int MAX_HISTORY_SIZE = 100; // 最大保存的历史记录数量
    
    public ChatHistoryManager() {
        this.chatHistory = new LinkedList<>();
        this.lock = new ReentrantReadWriteLock();
    }
    
    /**
     * 添加聊天消息
     * 
     * @param playerName 玩家名称
     * @param message 消息内容
     */
    public void addMessage(String playerName, String message) {
        if (message == null || message.trim().isEmpty()) {
            return;
        }
        
        lock.writeLock().lock();
        try {
            ChatMessage chatMessage = new ChatMessage(playerName, message.trim(), System.currentTimeMillis());
            chatHistory.addLast(chatMessage);
            
            // 保持历史记录大小在限制范围内
            while (chatHistory.size() > MAX_HISTORY_SIZE) {
                chatHistory.removeFirst();
            }
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * 获取最近的聊天消息
     * 
     * @param count 要获取的消息数量
     * @return 最近的聊天消息列表
     */
    public List<String> getRecentMessages(int count) {
        if (count <= 0) {
            return Collections.emptyList();
        }
        
        lock.readLock().lock();
        try {
            List<String> recentMessages = new ArrayList<>();
            int size = chatHistory.size();
            int startIndex = Math.max(0, size - count);
            
            for (int i = startIndex; i < size; i++) {
                ChatMessage chatMessage = chatHistory.get(i);
                String formattedMessage = formatMessage(chatMessage);
                recentMessages.add(formattedMessage);
            }
            
            return recentMessages;
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * 获取最近的聊天消息（排除 AI 消息）
     * 
     * @param count 要获取的消息数量
     * @param aiName AI 的名称
     * @return 最近的非 AI 聊天消息列表
     */
    public List<String> getRecentMessagesExcludingAi(int count, String aiName) {
        if (count <= 0) {
            return Collections.emptyList();
        }
        
        lock.readLock().lock();
        try {
            List<String> recentMessages = new ArrayList<>();
            
            // 从最新的消息开始向前查找
            for (int i = chatHistory.size() - 1; i >= 0 && recentMessages.size() < count; i--) {
                ChatMessage chatMessage = chatHistory.get(i);
                
                // 排除 AI 的消息
                if (!chatMessage.getPlayerName().equals(aiName)) {
                    String formattedMessage = formatMessage(chatMessage);
                    recentMessages.add(0, formattedMessage); // 添加到列表开头以保持时间顺序
                }
            }
            
            return recentMessages;
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * 格式化聊天消息
     */
    private String formatMessage(ChatMessage chatMessage) {
        return chatMessage.getPlayerName() + ": " + chatMessage.getMessage();
    }
    
    /**
     * 清空聊天历史
     */
    public void clearHistory() {
        lock.writeLock().lock();
        try {
            chatHistory.clear();
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * 获取历史记录大小
     */
    public int getHistorySize() {
        lock.readLock().lock();
        try {
            return chatHistory.size();
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * 聊天消息数据类
     */
    private static class ChatMessage {
        private final String playerName;
        private final String message;
        private final long timestamp;
        
        public ChatMessage(String playerName, String message, long timestamp) {
            this.playerName = playerName;
            this.message = message;
            this.timestamp = timestamp;
        }
        
        public String getPlayerName() {
            return playerName;
        }
        
        public String getMessage() {
            return message;
        }
        
        public long getTimestamp() {
            return timestamp;
        }
    }
}
