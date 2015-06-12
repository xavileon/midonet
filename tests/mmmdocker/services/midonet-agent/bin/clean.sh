for i in $(ip netns); do
    ip netns del $i
done

for i in $(ps aux|grep tcp|awk '{print $2}'); do
    kill -9 $i
done

