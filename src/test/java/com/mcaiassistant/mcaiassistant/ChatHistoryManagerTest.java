package com.mcaiassistant.mcaiassistant;

import java.util.List;

/**
 * 简单的聊天历史管理器测试
 * 由于没有完整的测试框架，这里只是一个基本的功能验证
 */
public class ChatHistoryManagerTest {
    
    public static void main(String[] args) {
        System.out.println("开始测试 ChatHistoryManager...");
        
        ChatHistoryManager manager = new ChatHistoryManager();
        
        // 测试添加消息
        manager.addMessage("Player1", "Hello world!");
        manager.addMessage("Player2", "How are you?");
        manager.addMessage("AI助手", "I'm fine, thank you!");
        manager.addMessage("Player1", "What's the weather like?");
        
        // 测试获取最近消息
        List<String> recentMessages = manager.getRecentMessages(3);
        System.out.println("最近3条消息:");
        for (String message : recentMessages) {
            System.out.println("  " + message);
        }
        
        // 测试排除 AI 消息
        List<String> recentMessagesExcludingAi = manager.getRecentMessagesExcludingAi(3, "AI助手");
        System.out.println("\n最近3条非AI消息:");
        for (String message : recentMessagesExcludingAi) {
            System.out.println("  " + message);
        }
        
        // 测试历史记录大小
        System.out.println("\n历史记录总数: " + manager.getHistorySize());
        
        System.out.println("\nChatHistoryManager 测试完成!");
    }
}
