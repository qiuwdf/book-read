package com.qiuwdf.readbook.core;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 分组文件夹树：把书架里的 groupPath（相对存储目录的文件夹路径，"a/b" 形式）
 * 组织成树，供全屏的树状分组选择页使用。
 *
 * <p>纯数据结构、无 Android 依赖，可直接单测。
 */
public final class GroupTree {

    /** 树节点：一个文件夹（根节点代表「全部」） */
    public static class Node {
        /** 文件夹名；根节点为 "" */
        public final String name;
        /** 相对存储目录的路径；根节点为 "" */
        public final String path;
        /** 层级：根（全部）= 0，一级文件夹 = 1，以此类推 */
        public final int depth;
        public final List<Node> children = new ArrayList<Node>();
        public Node parent;
        /** 本文件夹及其所有子文件夹内的书籍总数 */
        public int bookCount;
        /** 是否展开（只影响展示） */
        public boolean expanded;

        Node(String name, String path, int depth, Node parent) {
            this.name = name;
            this.path = path;
            this.depth = depth;
            this.parent = parent;
        }

        /** 是否有子文件夹 */
        public boolean hasChildren() {
            return !children.isEmpty();
        }
    }

    private static final Comparator<Node> BY_NAME = new Comparator<Node>() {
        final Collator col = Collator.getInstance(Locale.CHINA);

        @Override
        public int compare(Node a, Node b) {
            return col.compare(a.name, b.name);
        }
    };

    private GroupTree() {
    }

    /**
     * 由书籍列表构建分组树。
     *
     * <p>groupPath 按 "/" 逐级建链，中间目录（本身没有书、但子目录有书）也会成节点；
     * bookCount 逐级向上累加，因此每个节点的计数都是「该文件夹子树内的书总数」；
     * 同层文件夹按中文名排序。默认展开到第二层（一级文件夹展开、更深的收起）。
     */
    public static Node build(List<Book> books) {
        Node root = new Node("", "", 0, null);
        Map<String, Node> byPath = new HashMap<String, Node>();
        byPath.put("", root);

        if (books != null) {
            for (Book b : books) {
                String group = b.groupPath == null ? "" : b.groupPath;
                Node leaf = ensureChain(root, byPath, group);
                leaf.bookCount++;
            }
        }
        finalize_(root);
        return root;
    }

    /** 按 "a/b/c" 逐级找到（或创建）叶子节点 */
    private static Node ensureChain(Node root, Map<String, Node> byPath, String group) {
        if (group.length() == 0) {
            return root;
        }
        String[] segs = group.split("/");
        Node cur = root;
        String path = "";
        for (String seg : segs) {
            if (seg.length() == 0) {
                continue;   // 容忍 "a//b" 这类异常路径
            }
            path = path.length() == 0 ? seg : path + "/" + seg;
            Node next = byPath.get(path);
            if (next == null) {
                next = new Node(seg, path, cur.depth + 1, cur);
                cur.children.add(next);
                byPath.put(path, next);
            }
            cur = next;
        }
        return cur;
    }

    /** 后序遍历：子树计数向上累加、同层排序、按层级设置默认展开状态 */
    private static int finalize_(Node node) {
        int total = node.bookCount;
        if (!node.children.isEmpty()) {
            Collections.sort(node.children, BY_NAME);
            for (Node c : node.children) {
                total += finalize_(c);
            }
        }
        node.bookCount = total;
        // 全部(0) 与一级文件夹默认展开，更深的层级收起
        node.expanded = node.depth <= 1;
        return total;
    }

    /** 收集当前展开状态下应显示的节点（深度优先，含根） */
    public static void collectVisible(Node node, List<Node> out) {
        out.add(node);
        if (node.expanded) {
            for (Node c : node.children) {
                collectVisible(c, out);
            }
        }
    }

    /** 把到 target 路径上的所有祖先展开（用于打开页面时显示当前选中项） */
    public static void reveal(Node root, String path) {
        Node n = findByPath(root, path);
        while (n != null && n.parent != null) {
            n.parent.expanded = true;
            n = n.parent;
        }
    }

    /** 按相对路径查找节点 */
    public static Node findByPath(Node root, String path) {
        if (path == null || path.length() == 0) {
            return root;
        }
        for (Node c : root.children) {
            if (c.path.equals(path)) {
                return c;
            }
            Node hit = findByPath(c, path);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }
}
