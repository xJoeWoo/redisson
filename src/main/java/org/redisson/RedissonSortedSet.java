/**
 * Copyright 2014 Nikita Koksharov, Nickolay Borbit
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.redisson;

import java.io.ByteArrayOutputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.SortedSet;

import org.redisson.connection.ConnectionManager;
import org.redisson.core.RSortedSet;

import com.lambdaworks.redis.RedisConnection;
import com.lambdaworks.redis.ScoredValue;

/**
 *
 * @author Nikita Koksharov
 *
 * @param <V>
 */
public class RedissonSortedSet<V> extends RedissonObject implements RSortedSet<V> {

    private static class NaturalComparator<V> implements Comparator<V>, Serializable {

        private static final long serialVersionUID = 7207038068494060240L;

        static final NaturalComparator NATURAL_ORDER = new NaturalComparator();

        public int compare(V c1, V c2) {
            Comparable<Object> c1co = (Comparable<Object>) c1;
            Comparable<Object> c2co = (Comparable<Object>) c2;
            return c1co.compareTo(c2co);
        }

    }

    public static class NewScore {

        private Double leftScore;
        private Double rightScore;
        private Double score;

        public NewScore(Double leftScore, Double rightScore, Double score) {
            super();
            this.leftScore = leftScore;
            this.rightScore = rightScore;
            this.score = score;
        }

        public Double getLeftScore() {
            return leftScore;
        }

        public Double getRightScore() {
            return rightScore;
        }

        public Double getScore() {
            return score;
        }

    }

    public static class BinarySearchResult<V> {

        private V value;
        private Double score;
        private int index;

        public BinarySearchResult(V value, double score) {
            super();
            this.value = value;
            this.score = score;
        }

        public BinarySearchResult() {
        }

        public void setIndex(int index) {
            this.index = index;
        }
        public int getIndex() {
            return index;
        }

        public V getValue() {
            return value;
        }

        public Double getScore() {
            return score;
        }

    }

    private Comparator<? super V> comparator = NaturalComparator.NATURAL_ORDER;

    RedissonSortedSet(ConnectionManager connectionManager, String name) {
        super(connectionManager, name);

        loadComparator();

        RedisConnection<Object, Object> conn = connectionManager.connectionWriteOp();
        try {
            conn.setnx(getCurrentVersionKey(), 0L);
        } finally {
            connectionManager.releaseWrite(conn);
        }
    }

    private void loadComparator() {
        RedisConnection<Object, String> connection = connectionManager.connectionReadOp();
        try {
            loadComparator(connection);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            connectionManager.releaseRead(connection);
        }
    }

    private void loadComparator(RedisConnection<Object, ?> connection) {
        try {
            String comparatorSign = (String) connection.get(getComparatorKeyName());
            if (comparatorSign != null) {
                String[] parts = comparatorSign.split(":");
                String className = parts[0];
                String sign = parts[1];

                String result = calcClassSign(className);
                if (!result.equals(sign)) {
                    throw new IllegalStateException("Local class signature of " + className + " differs from used by this SortedSet!");
                }

                Class<?> clazz = Class.forName(className);
                comparator = (Comparator<V>) clazz.newInstance();
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // TODO cache result
    private static String calcClassSign(String name) {
        try {
            Class<?> clazz = Class.forName(name);

            ByteArrayOutputStream result = new ByteArrayOutputStream();
            ObjectOutputStream outputStream = new ObjectOutputStream(result);
            outputStream.writeObject(clazz);
            outputStream.close();

            MessageDigest crypt = MessageDigest.getInstance("SHA-1");
            crypt.reset();
            crypt.update(result.toByteArray());

            return new BigInteger(1, crypt.digest()).toString(16);
        } catch (Exception e) {
            throw new IllegalStateException("Can't calculate sign of " + name, e);
        }
    }

    @Override
    public int size() {
        RedisConnection<Object, V> connection = connectionManager.connectionReadOp();
        try {
            return size(connection);
        } finally {
            connectionManager.releaseRead(connection);
        }
    }

    private int size(RedisConnection<Object, ?> connection) {
        return connection.zcard(getName()).intValue();
    }

    @Override
    public boolean isEmpty() {
        return size() == 0;
    }

    @Override
    public boolean contains(Object o) {
        RedisConnection<Object, V> connection = connectionManager.connectionReadOp();
        try {
            return binarySearch((V)o, connection).getIndex() >= 0;
        } finally {
            connectionManager.releaseRead(connection);
        }
    }

    public Iterator<V> iterator() {
        Double startScore;
        RedisConnection<Object, V> connection = connectionManager.connectionReadOp();
        try {
            startScore = getScoreAtIndex(0, connection);
        } finally {
            connectionManager.releaseRead(connection);
        }
        if (startScore == null) {
            return new Iterator<V>() {
                @Override
                public boolean hasNext() {
                    return false;
                }

                @Override
                public V next() {
                    throw new NoSuchElementException();
                }

                @Override
                public void remove() {
                }
            };
        }

        return iterator(startScore, Double.MAX_VALUE);
    }

    public Iterator<V> iterator(final double startScore, final double endScore) {
        return new Iterator<V>() {

            private boolean readNext = true;
            private Double currentScore;
            private boolean removeExecuted;
            private V value;

            @Override
            public boolean hasNext() {
                if (!readNext) {
                    return true;
                }
                RedisConnection<Object, V> connection = connectionManager.connectionReadOp();
                try {
                    Double score = startScore;
                    if (currentScore != null) {
                        score = currentScore;
                    }
                    // get next two values
                    List<ScoredValue<V>> values = connection.zrangebyscoreWithScores(getName(), score, endScore, 0, 2);
                    if (values.isEmpty()) {
                        return false;
                    }

                    int index = 0;
                    if (currentScore != null) {
                        ScoredValue<V> val = values.get(0);
                        if (currentScore.equals(val.score)) {
                            if (values.size() > 1) {
                                index = 1;
                            } else {
                                return false;
                            }
                        }
                    }
                    
                    ScoredValue<V> val = values.get(index);
                    value = val.value;
                    currentScore = val.score;
                    readNext = false;
                    return true;
                } finally {
                    connectionManager.releaseRead(connection);
                }
            }

            @Override
            public V next() {
                if (value == null) {
                    if (!hasNext()) {
                        throw new NoSuchElementException("Exhausted iterator");
                    }
                }
                removeExecuted = false;
                readNext = true;
                return value;
            }

            @Override
            public void remove() {
                if (removeExecuted) {
                    throw new IllegalStateException("Element been already deleted");
                }

                RedissonSortedSet.this.remove(value);
                removeExecuted = true;
            }

        };
    }

    @Override
    public Object[] toArray() {
        RedisConnection<Object, Object> connection = connectionManager.connectionReadOp();
        try {
            return connection.zrange(getName(), 0, -1).toArray();
        } finally {
            connectionManager.releaseRead(connection);
        }
    }

    @Override
    public <T> T[] toArray(T[] a) {
        RedisConnection<Object, Object> connection = connectionManager.connectionReadOp();
        try {
            return connection.zrange(getName(), 0, -1).toArray(a);
        } finally {
            connectionManager.releaseRead(connection);
        }
    }

    private String getCurrentVersionKey() {
        return "redisson__sortedset__version__" + getName();
    }

    private Long getCurrentVersion(RedisConnection<Object, Object> simpleConnection) {
        return ((Number)simpleConnection.get(getCurrentVersionKey())).longValue();
    }

    @Override
    public boolean add(V value) {
        RedisConnection<Object, V> connection = connectionManager.connectionWriteOp();
        try {
            return add(value, connection);
        } finally {
            connectionManager.releaseWrite(connection);
        }
    }

    boolean add(V value, RedisConnection<Object, V> connection) {
        RedisConnection<Object, Object> simpleConnection = (RedisConnection<Object, Object>)connection;
        while (true) {
            connection.watch(getComparatorKeyName());

            checkComparator(connection);

            Long version = getCurrentVersion(simpleConnection);
            BinarySearchResult<V> res = binarySearch(value, connection);
            if (res.getIndex() < 0) {
                if (!version.equals(getCurrentVersion(simpleConnection))) {
                    connection.unwatch();
                    continue;
                }
                NewScore newScore = calcNewScore(res.getIndex(), connection);
                if (!version.equals(getCurrentVersion(simpleConnection))) {
                    connection.unwatch();
                    continue;
                }

                String leftScoreKey = getScoreKeyName(newScore.getLeftScore());
                String rightScoreKey = getScoreKeyName(newScore.getRightScore());

                if (simpleConnection.setnx(leftScoreKey, 1)) {
                    if (!version.equals(getCurrentVersion(simpleConnection))) {
                        connection.unwatch();

                        connection.del(leftScoreKey);
                        continue;
                    }
                    if (rightScoreKey != null) {

                        if (!simpleConnection.setnx(rightScoreKey, 1)) {
                            connection.unwatch();

                            connection.del(leftScoreKey);
                            continue;
                        }
                    }
                } else {
                    connection.unwatch();
                    continue;
                }

                connection.multi();
//                System.out.println("adding: " + newScore.getScore() + " " + value);
                connection.zadd(getName(), newScore.getScore(), value);
                if (rightScoreKey != null) {
                    connection.del(leftScoreKey, rightScoreKey);
                } else {
                    connection.del(leftScoreKey);
                }
                connection.incr(getCurrentVersionKey());
                List<Object> re = connection.exec();
                if (re.size() == 3) {
                    Number val = (Number) re.get(0);
                    Long delCount = (Long) re.get(1);
                    if (rightScoreKey != null) {
                        if (delCount != 2) {
                            throw new IllegalStateException();
                        }
                    } else {
                        if (delCount != 1) {
                            throw new IllegalStateException();
                        }
                    }
                    return val != null && val.intValue() > 0;
                } else {
                    checkComparator(connection);
                }
            } else {
                connection.unwatch();
                return false;
            }
        }
    }

    private void checkComparator(RedisConnection<Object, ?> connection) {
        String comparatorSign = (String) connection.get(getComparatorKeyName());
        if (comparatorSign != null) {
            String[] vals = comparatorSign.split(":");
            String className = vals[0];
            if (!comparator.getClass().getName().equals(className)) {
                try {
                    loadComparator(connection);
                } finally {
                    connection.unwatch();
                }
            }
        }
    }

    public static double calcIncrement(double value) {
        BigDecimal b = BigDecimal.valueOf(value);
        BigDecimal r = b.remainder(BigDecimal.ONE);
        if (r.compareTo(BigDecimal.ZERO) == 0) {
            return 1;
        }
        double res = 1/Math.pow(10, r.scale());
        return res;
    }

    /**
     * score for first entry = 0
     * score for entry added before = head - calcIncrement(head)
     * score for entry added after = tail + calcIncrement(head)
     * score for entry inserted between head and tail = head + (tail - head) / 2
     *
     * @param index
     * @param connection
     * @return score for index
     */
    protected NewScore calcNewScore(int index, RedisConnection<Object, V> connection) {
        if (index >= 0) {
            throw new IllegalStateException("index should be negative, but value is " + index);
        }
        index = -(index + 1);

        Double leftScore = null;
        Double rightScore = null;
        Double score = getScoreAtIndex(index, connection);
        // check is upper postion
        if (index == 0) {
            if (score == null) {
                // first score
                score = 0D;
                leftScore = score;
            } else {
                // before head
                leftScore = score;
                score = BigDecimal.valueOf(score).add(BigDecimal.valueOf(calcIncrement(score)).negate()).doubleValue();
            }
        } else {
            leftScore = getScoreAtIndex(index-1, connection);
            if (score == null) {
                // after tail
                score = BigDecimal.valueOf(leftScore).add(BigDecimal.valueOf(calcIncrement(leftScore))).doubleValue();
            } else {
                rightScore = score;
                double s = BigDecimal.valueOf(rightScore).add(BigDecimal.valueOf(leftScore).negate()).doubleValue();
                if (s == 1) {
                    score = BigDecimal.valueOf(leftScore).add(BigDecimal.valueOf(0.1)).doubleValue();
                } else if (s > 1) {
                    score = leftScore + 1;
                } else if (s < 1) {
                    double inc = calcIncrement(s);
                    if (BigDecimal.valueOf(leftScore).add(BigDecimal.valueOf(inc)).compareTo(BigDecimal.valueOf(rightScore)) >= 0) {
                      // TODO check strange behavior
//                    if (leftScore + inc >= rightScore) {
                        score = BigDecimal.valueOf(leftScore).add(BigDecimal.valueOf(inc).divide(BigDecimal.TEN)).doubleValue();
                    } else {
                        score = BigDecimal.valueOf(leftScore).add(BigDecimal.valueOf(inc)).doubleValue();
                    }
                }
            }
        }
//        System.out.println("score: " + score + " left: " + leftScore + " right: " + rightScore);
        return new NewScore(leftScore, rightScore, score);
    }

    @Override
    public boolean remove(Object value) {
        RedisConnection<Object, V> connection = connectionManager.connectionWriteOp();
        try {
            return remove(value, connection);
        } finally {
            connectionManager.releaseWrite(connection);
        }
    }

    boolean remove(Object value, RedisConnection<Object, V> connection) {
        BinarySearchResult<V> res = binarySearch((V) value, connection);
        if (res.getIndex() < 0) {
            return false;
        }
        connection.multi();
        connection.zremrangebyscore(getName(), res.getScore(), res.getScore());
        connection.incr(getCurrentVersionKey());
        List<Object> result = connection.exec();
        if (result.size() == 2) {
            return ((Number)result.get(0)).longValue() > 0;
        } else {
            return false;
        }
    }

    @Override
    public boolean containsAll(Collection<?> c) {
        for (Object object : c) {
            if (!contains(object)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean addAll(Collection<? extends V> c) {
        boolean changed = false;
        for (V v : c) {
            if (add(v)) {
                changed = true;
            }
        }
        return changed;
    }

    @Override
    public boolean retainAll(Collection<?> c) {
        boolean changed = false;
        for (Object object : this) {
            if (!c.contains(object)) {
                remove(object);
                changed = true;
            }
        }
        return changed;
    }

    @Override
    public boolean removeAll(Collection<?> c) {
        boolean changed = false;
        for (Object obj : c) {
            if (remove(obj)) {
                changed = true;
            }
        }
        return changed;
    }

    @Override
    public void clear() {
        RedisConnection<Object, Object> connection = connectionManager.connectionWriteOp();
        try {
            connection.del(getName());
        } finally {
            connectionManager.releaseWrite(connection);
        }
    }

    @Override
    public Comparator<? super V> comparator() {
        return comparator;
    }

    @Override
    public SortedSet<V> subSet(V fromElement, V toElement) {
        return new RedissonSubSortedSet<V>(this, connectionManager, fromElement, toElement);
    }

    @Override
    public SortedSet<V> headSet(V toElement) {
        return subSet(null, toElement);
    }

    @Override
    public SortedSet<V> tailSet(V fromElement) {
        return subSet(fromElement, null);
    }

    @Override
    public V first() {
        RedisConnection<Object, V> connection = connectionManager.connectionReadOp();
        try {
            BinarySearchResult<V> res = getAtIndex(0, connection);
            if (res.getScore() == null) {
                throw new NoSuchElementException();
            }
            return res.getValue();
        } finally {
            connectionManager.releaseRead(connection);
        }
    }

    @Override
    public V last() {
        // TODO refactor to -inf +inf
        RedisConnection<Object, V> connection = connectionManager.connectionReadOp();
        try {
            BinarySearchResult<V> res = getAtIndex(-1, connection);
            if (res.getScore() == null) {
                throw new NoSuchElementException();
            }
            return res.getValue();
        } finally {
            connectionManager.releaseRead(connection);
        }
    }

    private String getScoreKeyName(Double score) {
        if (score == null) {
            return null;
        }
        return "redisson__sortedset__score__" + getName() + "__" + score;
    }

    private String getComparatorKeyName() {
        return "redisson__sortedset__comparator__" + getName();
    }

    @Override
    public boolean trySetComparator(Comparator<? super V> comparator) {
        RedisConnection<Object, String> connection = connectionManager.connectionWriteOp();
        try {
            connection.watch(getName(), getComparatorKeyName());
            if (size(connection) > 0) {
                connection.unwatch();
                return false;
            }
            connection.multi();

            String className = comparator.getClass().getName();
            String comparatorSign = className + ":" + calcClassSign(className);
            connection.set(getComparatorKeyName(), comparatorSign);
            List<Object> res = connection.exec();
            if (res.size() == 1) {
                this.comparator = comparator;
                return true;
            }
            return false;
        } finally {
            connectionManager.releaseWrite(connection);
        }
    }

    private Double getScoreAtIndex(int index, RedisConnection<Object, V> connection) {
        List<ScoredValue<V>> res = connection.zrangeWithScores(getName(), index, index);
        if (res.isEmpty()) {
            return null;
        }
        return res.get(0).score;
    }

    private BinarySearchResult<V> getAtIndex(int index, RedisConnection<Object, V> connection) {
        List<ScoredValue<V>> res = connection.zrangeWithScores(getName(), index, index);
        if (res.isEmpty()) {
            return new BinarySearchResult<V>();
        }
        return new BinarySearchResult<V>(res.get(0).value, res.get(0).score);
    }

    /**
     * Binary search algorithm
     *
     * @param value
     * @param connection
     * @param lowerIndex
     * @param upperIndex
     * @return
     */
    private BinarySearchResult<V> binarySearch(V value, RedisConnection<Object, V> connection, int lowerIndex, int upperIndex) {
        while (lowerIndex <= upperIndex) {
            int index = lowerIndex + (upperIndex - lowerIndex) / 2;

            BinarySearchResult<V> indexRes = getAtIndex(index, connection);
            int cmp = comparator.compare(value, indexRes.getValue());

            if (cmp == 0) {
                indexRes.setIndex(index);
                return indexRes;
            } else if (cmp < 0) {
                upperIndex = index - 1;
            } else {
                lowerIndex = index + 1;
            }
        }

        BinarySearchResult<V> indexRes = new BinarySearchResult<V>();
        indexRes.setIndex(-(lowerIndex + 1));
        return indexRes;
    }

    public BinarySearchResult<V> binarySearch(V value, RedisConnection<Object, V> connection) {
        int upperIndex = size(connection) - 1;
        return binarySearch(value, connection, 0, upperIndex);
    }

    public double score(V value, RedisConnection<Object, V> connection, int indexDiff, boolean tail) {
        BinarySearchResult<V> res = binarySearch(value, connection);
        if (res.getIndex() < 0) {
            BinarySearchResult<V> element = getAtIndex(-res.getIndex() + indexDiff, connection);
            if (element.getScore() == null && res.getScore() == null && tail) {
                element = getAtIndex(-res.getIndex() - 2, connection);
                return element.getScore();
            }
            return element.getScore();
        }
        int ind = res.getIndex();
        if (tail) {
            ind = res.getIndex() - indexDiff;
        }
        BinarySearchResult<V> element = getAtIndex(ind, connection);
        return element.getScore();
    }

    public String toString() {
        Iterator<V> it = iterator();
        if (! it.hasNext())
            return "[]";

        StringBuilder sb = new StringBuilder();
        sb.append('[');
        for (;;) {
            V e = it.next();
            sb.append(e == this ? "(this Collection)" : e);
            if (! it.hasNext())
                return sb.append(']').toString();
            sb.append(',').append(' ');
        }
    }

}
