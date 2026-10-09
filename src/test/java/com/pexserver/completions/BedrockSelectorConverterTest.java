package com.pexserver.completions;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class BedrockSelectorConverterTest {
    private static void converts(String before, String after) {
        assertEquals(after, BedrockSelectorConverter.rewrite(before));
    }

    @Test void distance() {
        converts("/kill @e[r=20]", "/kill @e[distance=..20]");
        converts("/kill @e[rm=5]", "/kill @e[distance=5..]");
        converts("/kill @e[r=20,rm=5]", "/kill @e[distance=5..20]");
        converts("/kill @e[rm=5,r=5]", "/kill @e[distance=5]");
        converts("/kill @e[rm=5.25,r=20.5]", "/kill @e[distance=5.25..20.5]");
        converts("/kill @e[r=3,type=minecraft:zombie,rm=1]", "/kill @e[type=minecraft:zombie,distance=1..3]");
    }

    @Test void countAndSort() {
        converts("/kill @e[c=2]", "/kill @e[limit=2,sort=nearest]");
        converts("/kill @e[c=-2]", "/kill @e[limit=2,sort=furthest]");
        converts("/effect give @r[c=2] speed", "/effect give @r[limit=2,sort=random] speed");
        converts("/kill @p[c=2]", "/kill @p[limit=2,sort=nearest]");
        converts("/kill @a[c=1]", "/kill @a[limit=1,sort=nearest]");
    }

    @Test void gamemodeAndLevel() {
        converts("/kill @a[m=0]", "/kill @a[gamemode=survival]");
        converts("/kill @a[m=!1]", "/kill @a[gamemode=!creative]");
        converts("/kill @a[m=adventure]", "/kill @a[gamemode=adventure]");
        converts("/kill @a[lm=10,l=25]", "/kill @a[level=10..25]");
        converts("/kill @a[lm=10]", "/kill @a[level=10..]");
        converts("/kill @a[l=25]", "/kill @a[level=..25]");
        converts("/kill @a[l=3,lm=3]", "/kill @a[level=3]");
    }

    @Test void rotation() {
        converts("/kill @e[rxm=-35,rx=45]", "/kill @e[x_rotation=-35..45]");
        converts("/kill @e[ry=90]", "/kill @e[y_rotation=..90]");
        converts("/kill @a[rym=-180,ry=180]", "/kill @a");
        converts("/kill @a[ry=180]", "/kill @a");
        converts("/kill @e[rym=-120]", "/kill @e[y_rotation=-120..]");
        converts("/kill @e[rx=-45]", "/kill @e[x_rotation=..-45]");
    }

    @Test void passesJavaSelectorsThrough() {
        String[] commands = {
                "/execute if entity @e[distance=..20,limit=1,sort=nearest] run say yes",
                "/kill @e[gamemode=survival,level=10..,x_rotation=-10..10]",
                "/kill @e[nbt={CustomName:'{\"text\":\"hi\"}'},scores={x=1..5,y=2}]",
                "/kill @e[predicate=minecraft:night,tag=!friendly]",
                "/kill @e[distance=..2] @a[type=minecraft:player]"
        };
        for (String command : commands) assertEquals(command, BedrockSelectorConverter.rewrite(command));
    }

    @Test void handlesComplexValuesAndMultipleSelectors() {
        converts("/execute as @e[scores={a=1,b=2},r=10] at @a[m=creative] run say yes",
                "/execute as @e[scores={a=1,b=2},distance=..10] at @a[gamemode=creative] run say yes");
        converts("/kill @e[name=\"hello, world\",r=5]", "/kill @e[name=\"hello, world\",distance=..5]");
        converts("/kill @e[nbt={Items:[{id:\"stone\",Count:1b}]},r=4]",
                "/kill @e[nbt={Items:[{id:\"stone\",Count:1b}]},distance=..4]");
    }

    @Test void doesNotChangeStringsOrMalformedSelectors() {
        String[] unchanged = {
                "/say \"@e[r=4]\"",
                "/tellraw @a {\"text\":\"@e[r=4]\"}",
                "/kill @e[r=]",
                "/kill @e[r=4",
                "/kill @e[rxm=10,rx=-10]",
                "/kill @e[rm=7,r=3]",
                "/kill @e[r=-1]",
                "/kill @e[l=2.5]",
                "/kill @e[c=0]",
                "/kill @e[c=2147483648]",
                "/kill @s[c=1]",
                "/kill @r[type=cow,r=3]",
                "/kill @r[c=-3]",
                "/kill @e[m=nonexistent]",
                "/kill @e[ry=270]",
                "/kill @a[rym=0,ry=180]",
                "/kill @a[rym=180]",
                "/kill @e[family=mob,r=5]",
                "/kill @e[hasitem={item=stone},r=5]",
                "/kill @e[distance=..10,r=10]",
                "/kill @e[limit=2,c=2]",
                "/kill @e[sort=random,c=2]",
                "/kill @e[rm=5,rm=5]",
                "/kill player@e[r=3]",
                "/kill @initiator[r=3]"
        };
        for (String command : unchanged) assertEquals(command, BedrockSelectorConverter.rewrite(command), command);
    }

    @Test void preservesOtherJavaArgumentsWhenConverting() {
        converts("/kill @e[r=10,sort=random,tag=test]", "/kill @e[sort=random,tag=test,distance=..10]");
        converts("/kill @e[m=1,scores={quest=5..},tag=ok]", "/kill @e[scores={quest=5..},tag=ok,gamemode=creative]");
        converts("/kill @e[distance=..3,c=2]", "/kill @e[distance=..3,limit=2,sort=nearest]");
        converts("/execute as @e[r=3] run say @e[r=5]",
                "/execute as @e[distance=..3] run say @e[r=5]");
    }
}
