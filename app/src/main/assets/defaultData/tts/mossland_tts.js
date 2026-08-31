// @name Mossland
// @schema 1
// @version 1.3.0
// @uuid mossland_moss_tts
// @author Legado NG
// @url https://api.mosi.cn/v1/audio/speech
// @enabled false
// @cookieJar false
// @audioType audio/mpeg
// @defaultSpeed 50
// @defaultVolume 50
// @defaultPitch 50
// @maxConcurrency 2
// @sampleText 前不见古人，后不见来者。念天地之悠悠，独怆然而涕下。
// @capabilities casting_metadata
// @description Mossland 单人语音合成。内置 VV 全量复刻音色及画像，不在运行时请求发音人目录。

var MOSS_DEFAULT_BASE_URL = "https://api.mosi.cn";
var MOSS_VOICES = [
    {
        "id": "771aaeaf-566d-45a2-b57e-1df102349e5f",
        "name": "说书先生",
        "language": "zh-CN",
        "gender": "male",
        "style": "旁白",
        "tags": [
            "有声书",
            "中年",
            "旁白",
            "40-55岁"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "771aaeaf-566d-45a2-b57e-1df102349e5f",
            "age_stage": "mature",
            "age_min": 40,
            "age_max": 55,
            "accent": "standard-mandarin",
            "group": "旁白",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "",
            "vv_style": "旁白",
            "vv_tags": [
                "旁白",
                "40-55岁"
            ],
            "persona": "[#设定：男声，年龄40-55岁。强制物理级锁定醇厚沉稳中低音绝对频段，底层强制开启宽厚饱满胸腔共鸣与苍劲通透喉结发音，彻底屏蔽轻浮跳脱、尖锐刺耳与稚嫩单薄感，全程评书腔调。赋予声音在中低音区岁月基底上醇厚稳重、抑扬顿挫的独特质感，声线浑厚有力，带岁月磨砂颗粒感，中气十足老练通透，绝对禁止稚嫩、禁止沙哑、禁止单薄伪音。咬字顿挫分明，语速张弛有度，尾音利落收束，语气沉稳大气，能在绝对纯正中年音域内完成评书叙事、悬念铺垫、生动演绎、感慨点评，绝不稚嫩轻浮、绝不尖锐刺耳。评书先生感、岁月沉淀感、看似沉稳老练实则声情并茂的中年男声，说话带贴耳醇厚呼吸感，用焊死中低音的极致厚重感，打造传统评书故事感中年声线。]"
        }
    },
    {
        "id": "cc6b8c4e-5827-4ebc-bbbb-71fd700c1823",
        "name": "浅吟治愈",
        "language": "zh-CN",
        "gender": "female",
        "style": "温柔·自然·慵懒",
        "tags": [
            "有声书",
            "青年",
            "旁白",
            "20-24岁",
            "温柔",
            "自然",
            "慵懒"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "cc6b8c4e-5827-4ebc-bbbb-71fd700c1823",
            "age_stage": "young_adult",
            "age_min": 20,
            "age_max": 24,
            "accent": "standard-mandarin",
            "group": "旁白",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "轻浅柔和、气息舒缓的青年女声，安静恬淡，适合有声书中的治愈陪伴角色。",
            "vv_style": "旁白",
            "vv_tags": [
                "旁白",
                "20-24岁"
            ],
            "persona": "[#设定：女声，年龄20-24岁。强制物理级锁定低柔浅缓轻音，微弱柔气息，彻底屏蔽洪亮、亢奋、锐利。声线轻浅温柔，咬字舒缓轻柔，气质安静恬淡，适合冥想助眠、轻语陪伴角色。]"
        }
    },
    {
        "id": "2395e906-cb28-4a9c-9e74-dc19747082fe",
        "name": "密语甜御",
        "language": "zh-CN",
        "gender": "female",
        "style": "甜美·温柔·性感",
        "tags": [
            "有声书",
            "青年",
            "旁白",
            "24-29岁",
            "甜美",
            "温柔",
            "性感"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "2395e906-cb28-4a9c-9e74-dc19747082fe",
            "age_stage": "young_adult",
            "age_min": 24,
            "age_max": 29,
            "accent": "standard-mandarin",
            "group": "旁白",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "柔润清甜、带轻熟蜜感的青年女声，婉转温柔，适合有声书中的甜御系角色。",
            "vv_style": "旁白",
            "vv_tags": [
                "旁白",
                "24-29岁"
            ],
            "persona": "[#设定：女声，年龄24-29岁。强制物理级锁定蜜感柔润中低音，绵密柔共鸣，彻底屏蔽单薄、尖锐、冰冷。声线柔媚清甜，咬字婉转柔和，气质轻熟妩媚，适合甜御系、温柔姐姐角色。]"
        }
    },
    {
        "id": "8e5e71ab-f671-4285-8e41-b8e52e7c21f3",
        "name": "飒爽御姐",
        "language": "zh-CN",
        "gender": "female",
        "style": "专业·沉稳·自然",
        "tags": [
            "有声书",
            "青年",
            "旁白",
            "23-28岁",
            "专业",
            "沉稳",
            "自然"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "8e5e71ab-f671-4285-8e41-b8e52e7c21f3",
            "age_stage": "young_adult",
            "age_min": 23,
            "age_max": 28,
            "accent": "standard-mandarin",
            "group": "旁白",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "干脆利落、英气果决的青年女声，声线紧致有力，适合有声书中的职场与御姐角色。",
            "vv_style": "旁白",
            "vv_tags": [
                "旁白",
                "23-28岁"
            ],
            "persona": "[#设定：女声，年龄23-28岁。强制物理级锁定利落劲爽中低音，紧致实声无浮气，彻底屏蔽娇嗲、软绵、空洞。声线干脆凌厉，咬字干练果决，气质英气干练，适合职场精英、飒爽御姐角色。]"
        }
    },
    {
        "id": "834b528a-6713-42a9-b203-53a389bb509f",
        "name": "幼态童声",
        "language": "zh-CN",
        "gender": "female",
        "style": "活泼·甜美·轻快",
        "tags": [
            "有声书",
            "少年",
            "女童",
            "6-9岁",
            "活泼",
            "甜美",
            "轻快"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "834b528a-6713-42a9-b203-53a389bb509f",
            "age_stage": "teen",
            "age_min": 6,
            "age_max": 9,
            "accent": "standard-mandarin",
            "group": "女童",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "尖脆稚嫩、天真懵懂的女童声，音调清亮偏高，适合有声书中的低龄儿童角色。",
            "vv_style": "女童",
            "vv_tags": [
                "女童",
                "6-9岁"
            ],
            "persona": "[#设定：女声，年龄6-9岁。强制物理级锁定幼态头腔超高尖频基底，浅层单薄震动发声，彻底屏蔽胸腔低频、厚沉声质、成熟共鸣。声基底细尖脆嫩，音调偏高，咬字短碎稚嫩，气质天真懵懂，高频尖脆低龄女童声。]"
        }
    },
    {
        "id": "f67deb5f-652b-471a-9c52-52265acb6add",
        "name": "早熟童音",
        "language": "zh-CN",
        "gender": "female",
        "style": "沉稳·自然·高冷",
        "tags": [
            "有声书",
            "少年",
            "女童",
            "8-12岁",
            "沉稳",
            "自然",
            "高冷"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "f67deb5f-652b-471a-9c52-52265acb6add",
            "age_stage": "teen",
            "age_min": 8,
            "age_max": 12,
            "accent": "standard-mandarin",
            "group": "女童",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "偏低沉稳、安静克制的女童声，带清冷早熟感，适合有声书中的早慧儿童角色。",
            "vv_style": "女童",
            "vv_tags": [
                "女童",
                "8-12岁"
            ],
            "persona": "[#设定：女声，年龄8-12岁。强制物理级锁定下沉低位胸腔童声基底，低中频紧实实声，彻底屏蔽尖细高音、轻飘气声、幼态窄频。声基底偏低偏沉，声感稳重，咬字平缓克制，气质安静早熟，低频厚感清冷女童嗓。]"
        }
    },
    {
        "id": "bf13826a-2fe5-43e0-b89f-0beba890b77f",
        "name": "软气童声",
        "language": "zh-CN",
        "gender": "female",
        "style": "温柔·甜美·自然",
        "tags": [
            "有声书",
            "少年",
            "女童",
            "5-8岁",
            "温柔",
            "甜美",
            "自然"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "bf13826a-2fe5-43e0-b89f-0beba890b77f",
            "age_stage": "teen",
            "age_min": 5,
            "age_max": 8,
            "accent": "standard-mandarin",
            "group": "女童",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "绵软轻浅、气息偏多的女童声，带胆小羞怯感，适合有声书中的内向儿童角色。",
            "vv_style": "女童",
            "vv_tags": [
                "女童",
                "5-8岁"
            ],
            "persona": "[#设定：女声，年龄5-8岁。强制物理级锁定浅层全气声幼龄基底，微弱浅息震动，彻底屏蔽扎实实声、强声压、立体口腔共鸣。声基底绵软虚化，气息偏多，咬字轻浅无力，气质胆小羞怯，弱气软糯内向女童音。]"
        }
    },
    {
        "id": "a72ab491-4279-4f3a-a119-7ab0e80459ec",
        "name": "微哑童嗓",
        "language": "zh-CN",
        "gender": "female",
        "style": "自然·沉稳·慵懒",
        "tags": [
            "有声书",
            "少年",
            "女童",
            "9-12岁",
            "自然",
            "沉稳",
            "慵懒"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "a72ab491-4279-4f3a-a119-7ab0e80459ec",
            "age_stage": "teen",
            "age_min": 9,
            "age_max": 12,
            "accent": "standard-mandarin",
            "group": "女童",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "带轻微磨砂哑感的女童声，青涩质朴、松弛自然，适合有声书中的山野儿童角色。",
            "vv_style": "女童",
            "vv_tags": [
                "女童",
                "9-12岁"
            ],
            "persona": "[#设定：女声，年龄9-12岁。强制物理级锁定轻微声带磨砂幼态基底，浅哑低颗粒质感，彻底屏蔽纯白亮嗓、高甜嫩音、顺滑薄声。声基底自带淡淡哑感，青涩质朴，咬字随性松弛，气质山野质朴，磨砂质感原生态女童声。]"
        }
    },
    {
        "id": "e5016a06-8fb1-4dbb-9a23-02da4956226a",
        "name": "透亮童声",
        "language": "zh-CN",
        "gender": "female",
        "style": "轻快·自然·温柔",
        "tags": [
            "有声书",
            "少年",
            "女童",
            "7-10岁",
            "轻快",
            "自然",
            "温柔"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "e5016a06-8fb1-4dbb-9a23-02da4956226a",
            "age_stage": "teen",
            "age_min": 7,
            "age_max": 10,
            "accent": "standard-mandarin",
            "group": "女童",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "清澈透亮、干净明快的女童声，带纯净阳光感，适合有声书中的治愈儿童角色。",
            "vv_style": "女童",
            "vv_tags": [
                "女童",
                "7-10岁"
            ],
            "persona": "[#设定：女声，年龄7-10岁。强制物理级锁定头腔通透纯净发声基底，高透无杂质高频，彻底屏蔽闷浊暗音、沙哑颗粒、厚重闷嗓。声基底清澈透亮，音色干净无瑕，咬字轻快干净，气质纯净阳光，透亮治愈系女童嗓。]"
        }
    },
    {
        "id": "c490481d-b505-4989-9125-b1f0c0165a0f",
        "name": "压抑童音",
        "language": "zh-CN",
        "gender": "female",
        "style": "感伤·沉稳·高冷",
        "tags": [
            "有声书",
            "少年",
            "女童",
            "8-11岁",
            "感伤",
            "沉稳",
            "高冷"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "c490481d-b505-4989-9125-b1f0c0165a0f",
            "age_stage": "teen",
            "age_min": 8,
            "age_max": 11,
            "accent": "standard-mandarin",
            "group": "女童",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "暗沉内敛、含缩缓慢的女童声，带压抑孤僻感，适合有声书中的沉默儿童角色。",
            "vv_style": "女童",
            "vv_tags": [
                "女童",
                "8-11岁"
            ],
            "persona": "[#设定：女声，年龄8-11岁。强制物理级锁定喉位内收封闭闷声基底，内敛压抑中频，彻底屏蔽外放敞亮、高亢音调、活泼声质。声基底暗沉发闷，音色偏沉，咬字含缩缓慢，气质安静孤僻，压抑内向沉默女童声线。]"
        }
    },
    {
        "id": "46007782-9b25-492b-a6d4-e6a8ce7b4078",
        "name": "木讷童声",
        "language": "zh-CN",
        "gender": "female",
        "style": "自然·沉稳·高冷",
        "tags": [
            "有声书",
            "少年",
            "女童",
            "6-10岁",
            "自然",
            "沉稳",
            "高冷"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "46007782-9b25-492b-a6d4-e6a8ce7b4078",
            "age_stage": "teen",
            "age_min": 6,
            "age_max": 10,
            "accent": "standard-mandarin",
            "group": "女童",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "平直寡淡、起伏较少的女童声，带迟钝木讷感，适合有声书中的沉静儿童角色。",
            "vv_style": "女童",
            "vv_tags": [
                "女童",
                "6-10岁"
            ],
            "persona": "[#设定：女声，年龄6-10岁。强制物理级锁定扁平无起伏幼态声基，单调平直无共鸣频段，彻底屏蔽婉转腔调、起伏音色、柔和泛音。声基底平直呆板，音色寡淡单调，咬字生硬平缓，气质迟钝木讷，无情绪平板女童音。]"
        }
    },
    {
        "id": "74502931-f676-42ee-a0c8-793587fbba3f",
        "name": "软糯童嗓",
        "language": "zh-CN",
        "gender": "female",
        "style": "甜美·温柔·自然",
        "tags": [
            "有声书",
            "少年",
            "女童",
            "5-9岁",
            "甜美",
            "温柔",
            "自然"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "74502931-f676-42ee-a0c8-793587fbba3f",
            "age_stage": "teen",
            "age_min": 5,
            "age_max": 9,
            "accent": "standard-mandarin",
            "group": "女童",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "圆润软糯、乖巧甜美的稚嫩女声，适合儿童角色与有声书对白。",
            "vv_style": "女童",
            "vv_tags": [
                "女童",
                "5-9岁"
            ],
            "persona": "[#设定：女声，年龄5-9岁。强制物理级锁定口腔圆腔聚拢发声基底，圆润饱满幼态中频，彻底屏蔽扁薄刺耳、尖锐窄声、干涩发硬。声基底圆润软和，口腔共鸣饱满，咬字圆钝软糯，气质乖巧甜美，圆腔软萌乖乖女童声。]"
        }
    },
    {
        "id": "df7fe941-4ea9-4cca-a83f-58ba1c0c7002",
        "name": "孱弱童声",
        "language": "zh-CN",
        "gender": "female",
        "style": "感伤·温柔·自然",
        "tags": [
            "有声书",
            "少年",
            "女童",
            "7-11岁",
            "感伤",
            "温柔",
            "自然"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "df7fe941-4ea9-4cca-a83f-58ba1c0c7002",
            "age_stage": "teen",
            "age_min": 7,
            "age_max": 11,
            "accent": "standard-mandarin",
            "group": "女童",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "细碎轻弱、气息略显不稳的女童声，带敏感易碎感，适合有声书中的体弱儿童角色。",
            "vv_style": "女童",
            "vv_tags": [
                "女童",
                "7-11岁"
            ],
            "persona": "[#设定：女声，年龄7-11岁。强制物理级锁定气虚断续薄弱基底，气息不稳弱震动，彻底屏蔽紧实发声、洪亮音量、连贯声线。声基底细碎断续，气息偏弱，咬字轻缓吃力，气质体弱敏感，孱弱易碎低龄女童嗓。]"
        }
    },
    {
        "id": "1aa7467f-d5e9-414e-9b56-7b62e3c1c16e",
        "name": "薄冷童音",
        "language": "zh-CN",
        "gender": "female",
        "style": "高冷·自然·沉稳",
        "tags": [
            "有声书",
            "少年",
            "女童",
            "9-12岁",
            "高冷",
            "自然",
            "沉稳"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "1aa7467f-d5e9-414e-9b56-7b62e3c1c16e",
            "age_stage": "teen",
            "age_min": 9,
            "age_max": 12,
            "accent": "standard-mandarin",
            "group": "女童",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "清冷淡薄、简短疏离的女童声，声线干净偏冷，适合有声书中的安静冷淡儿童角色。",
            "vv_style": "女童",
            "vv_tags": [
                "女童",
                "9-12岁"
            ],
            "persona": "[#设定：女声，年龄9-12岁。强制物理级锁定超薄冷感单薄声基，无多余共鸣干声，彻底屏蔽暖糯厚底、甜美音色、柔和包裹感。声基底清冷淡薄，色素净偏冷，咬字简短疏离，气质安静冷淡，寡欲清冷小众女童声。]"
        }
    },
    {
        "id": "f20d552d-9eba-4790-980e-7ecabb6c76b2",
        "name": "大方童声",
        "language": "zh-CN",
        "gender": "female",
        "style": "活泼·轻快·自然",
        "tags": [
            "有声书",
            "少年",
            "女童",
            "8-12岁",
            "活泼",
            "轻快",
            "自然"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "f20d552d-9eba-4790-980e-7ecabb6c76b2",
            "age_stage": "teen",
            "age_min": 8,
            "age_max": 12,
            "accent": "standard-mandarin",
            "group": "女童",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "敞亮外放、咬字利落的女童声，带开朗元气感，适合有声书中的活泼儿童角色。",
            "vv_style": "女童",
            "vv_tags": [
                "女童",
                "8-12岁"
            ],
            "persona": "[#设定：女声，年龄8-12岁。强制物理级锁定大气量开阔外放基底，舒展饱满浅胸腔共鸣，彻底屏蔽小气拘束、细弱音量、拘谨窄频。声基底敞亮洪亮，声压充足，咬字大方利落，气质活泼大方，开朗外放元气女童嗓。]"
        }
    },
    {
        "id": "5d2bbd56-2e46-488b-9bbb-add54645499d",
        "name": "细怯童嗓",
        "language": "zh-CN",
        "gender": "female",
        "style": "温柔·感伤·自然",
        "tags": [
            "有声书",
            "少年",
            "女童",
            "5-8岁",
            "温柔",
            "感伤",
            "自然"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "5d2bbd56-2e46-488b-9bbb-add54645499d",
            "age_stage": "teen",
            "age_min": 5,
            "age_max": 8,
            "accent": "standard-mandarin",
            "group": "女童",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "纤细轻缓、音量偏小的女童声，带胆怯怕生感，适合有声书中的敏感内向儿童角色。",
            "vv_style": "女童",
            "vv_tags": [
                "女童",
                "5-8岁"
            ],
            "persona": "[#设定：女声，年龄5-8岁。强制物理级锁定窄腔收缩局促发声基底，细弱狭窄音域，彻底屏蔽开阔大嗓、外放音量、硬质发力。声基底纤细狭窄，音量细小，咬字轻缓胆怯，气质胆小怕生，局促敏感内向女童音。]"
        }
    },
    {
        "id": "fc6a5190-31ca-46c7-a48f-a2fe22a7e89e",
        "name": "朦胧童声",
        "language": "zh-CN",
        "gender": "female",
        "style": "温柔·慵懒·自然",
        "tags": [
            "有声书",
            "少年",
            "女童",
            "7-10岁",
            "温柔",
            "慵懒",
            "自然"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "fc6a5190-31ca-46c7-a48f-a2fe22a7e89e",
            "age_stage": "teen",
            "age_min": 7,
            "age_max": 10,
            "accent": "standard-mandarin",
            "group": "女童",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "朦胧柔糯、轻缓绵软的女童声，带梦幻氛围感，适合有声书中的治愈童话角色。",
            "vv_style": "女童",
            "vv_tags": [
                "女童",
                "7-10岁"
            ],
            "persona": "[#设定：女声，年龄7-10岁。强制物理级锁定浅雾朦胧柔化基底，柔雾混合弱共鸣，彻底屏蔽直白实声、尖锐亮音、干净硬质感。声基底朦胧柔糯，音色柔和模糊，咬字轻缓绵软，气质梦幻治愈，氛围感朦胧女童声。]"
        }
    },
    {
        "id": "f91aaaf1-281a-4884-b6f5-8b6b09653643",
        "name": "倔强童音",
        "language": "zh-CN",
        "gender": "female",
        "style": "热血·活泼·自然",
        "tags": [
            "有声书",
            "少年",
            "女童",
            "9-12岁",
            "热血",
            "活泼",
            "自然"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "f91aaaf1-281a-4884-b6f5-8b6b09653643",
            "age_stage": "teen",
            "age_min": 9,
            "age_max": 12,
            "accent": "standard-mandarin",
            "group": "女童",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "干脆硬朗、咬字坚定的女童声，带倔强英气感，适合有声书中的勇敢儿童角色。",
            "vv_style": "女童",
            "vv_tags": [
                "女童",
                "9-12岁"
            ],
            "persona": "[#设定：女声，年龄9-12岁。强制物理级锁定紧实硬质发力幼态基底，利落刚性实声，彻底屏蔽软绵松散、娇弱气声、软糯拖沓。声基底干脆硬朗，发力感清晰，咬字短促坚定，气质倔强英气，元气倔强中性女童嗓。]"
        }
    },
    {
        "id": "482dc67f-0d95-4f77-b407-8809a4ef28ac",
        "name": "绵柔童声",
        "language": "zh-CN",
        "gender": "female",
        "style": "温柔·甜美·自然",
        "tags": [
            "有声书",
            "少年",
            "女童",
            "6-9岁",
            "温柔",
            "甜美",
            "自然"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "482dc67f-0d95-4f77-b407-8809a4ef28ac",
            "age_stage": "teen",
            "age_min": 6,
            "age_max": 9,
            "accent": "standard-mandarin",
            "group": "女童",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "温暖绵柔、黏软舒缓的女童声，带邻家治愈感，适合有声书中的乖巧儿童角色。",
            "vv_style": "女童",
            "vv_tags": [
                "女童",
                "6-9岁"
            ],
            "persona": "[#设定：女声，年龄6-9岁。强制物理级锁定暖调温润底层声基，柔和暖系中频，彻底屏蔽冷薄声底、冷锐音色、干涩生硬。声基底自带温暖底色，绵密柔和，咬字黏软舒缓，气质温顺治愈，暖心柔和邻家女童音。]"
        }
    },
    {
        "id": "4c27bb2b-b17d-4f02-8507-72e8ceed6e85",
        "name": "灵动童嗓",
        "language": "zh-CN",
        "gender": "female",
        "style": "活泼·轻快·甜美",
        "tags": [
            "有声书",
            "少年",
            "女童",
            "7-11岁",
            "活泼",
            "轻快",
            "甜美"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "4c27bb2b-b17d-4f02-8507-72e8ceed6e85",
            "age_stage": "teen",
            "age_min": 7,
            "age_max": 11,
            "accent": "standard-mandarin",
            "group": "女童",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "短促清脆、节奏跳跃的女童声，带调皮机灵感，适合有声书中的俏皮儿童角色。",
            "vv_style": "女童",
            "vv_tags": [
                "女童",
                "7-11岁"
            ],
            "persona": "[#设定：女声，年龄7-11岁。强制物理级锁定短音节碎脆发声基底，轻快割裂短频节奏，彻底屏蔽长腔拖音、缓慢沉缓、绵长共鸣。声基底短促清脆，节奏跳跃，咬字细碎灵动，气质调皮活泼，机灵好动俏皮女童声。]"
        }
    },
    {
        "id": "4d743381-3890-42df-bdf0-482dcd90b113",
        "name": "古风童音",
        "language": "zh-CN",
        "gender": "female",
        "style": "温柔·自然·沉稳",
        "tags": [
            "有声书",
            "少年",
            "女童",
            "8-12岁",
            "温柔",
            "自然",
            "沉稳"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "4d743381-3890-42df-bdf0-482dcd90b113",
            "age_stage": "teen",
            "age_min": 8,
            "age_max": 12,
            "accent": "standard-mandarin",
            "group": "女童",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "温婉含韵、尾音轻柔的女童声，带古典乖巧感，适合有声书中的古风儿童角色。",
            "vv_style": "女童",
            "vv_tags": [
                "女童",
                "8-12岁"
            ],
            "persona": "[#设定：女声，年龄8-12岁。强制物理级锁定浅韵婉转幼态腔底，轻柔古风折韵发声，彻底屏蔽直白大白嗓、生硬短音、浮躁节奏。声基底温婉含韵，尾音轻缓柔和，咬字含蓄雅致，气质古典乖巧，古风雅致小家女童嗓。]"
        }
    },
    {
        "id": "f2f2ebe0-69d2-4258-8f48-95b5051fcf32",
        "name": "机械童音",
        "language": "zh-CN",
        "gender": "female",
        "style": "高冷·专业·沉稳",
        "tags": [
            "有声书",
            "少年",
            "女童",
            "9-12岁",
            "高冷",
            "专业",
            "沉稳"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "f2f2ebe0-69d2-4258-8f48-95b5051fcf32",
            "age_stage": "teen",
            "age_min": 9,
            "age_max": 12,
            "accent": "standard-mandarin",
            "group": "女童",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "冰冷平直、节奏均匀的女童声，带机械人偶感，适合有声书中的理性或非人儿童角色。",
            "vv_style": "女童",
            "vv_tags": [
                "女童",
                "9-12岁"
            ],
            "persona": "[#设定：女声，年龄9-12岁。强制物理级锁定平直无感机械幼态基底，无起伏单一频段，彻底屏蔽童真软糯、情绪起伏、柔和泛音。声基底冰冷平整，节奏均匀刻板，咬字标准生硬，气质漠然无感，人偶式理性女童音。]"
        }
    },
    {
        "id": "83103071-9d04-435b-b1e8-944048b7cf84",
        "name": "温润童嗓",
        "language": "zh-CN",
        "gender": "female",
        "style": "温柔·自然·慵懒",
        "tags": [
            "有声书",
            "少年",
            "女童",
            "6-10岁",
            "温柔",
            "自然",
            "慵懒"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "83103071-9d04-435b-b1e8-944048b7cf84",
            "age_stage": "teen",
            "age_min": 6,
            "age_max": 10,
            "accent": "standard-mandarin",
            "group": "女童",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "湿软温润、轻慢松弛的女童声，带安静治愈感，适合有声书中的温柔儿童角色。",
            "vv_style": "女童",
            "vv_tags": [
                "女童",
                "6-10岁"
            ],
            "persona": "[#设定：女声，年龄6-10岁。强制物理级锁定湿润柔和幼态声质，低饱和柔润中低频，彻底屏蔽干燥尖嗓、脆硬颗粒、高亮刺耳。声基底湿软温润，音色柔和暗沉，咬字轻慢松弛，气质安静温柔，治愈系温润女童声。]"
        }
    },
    {
        "id": "1385aca4-9b36-42ed-a32e-9e79757f5b83",
        "name": "质朴童音",
        "language": "zh-CN",
        "gender": "female",
        "style": "自然·沉稳·轻快",
        "tags": [
            "有声书",
            "少年",
            "女童",
            "8-12岁",
            "自然",
            "沉稳",
            "轻快"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "1385aca4-9b36-42ed-a32e-9e79757f5b83",
            "age_stage": "teen",
            "age_min": 8,
            "age_max": 12,
            "accent": "standard-mandarin",
            "group": "女童",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "朴实直白、天然无修饰的女童声，带纯粹原生态感，适合有声书中的乡野儿童角色。",
            "vv_style": "女童",
            "vv_tags": [
                "女童",
                "8-12岁"
            ],
            "persona": "[#设定：女声，年龄8-12岁。强制物理级锁定原生态朴素平声基底，无修饰天然发声，彻底屏蔽精致甜嗓、刻意软萌、修饰共鸣。声基底朴实直白，音色天然无雕琢，咬字随性自然，气质纯粹野性，乡土质朴原生态女童嗓。]"
        }
    },
    {
        "id": "e9b03c9e-586e-4502-a6d2-c78e6ad2ae10",
        "name": "天真男孩",
        "language": "zh-CN",
        "gender": "male",
        "style": "自然·活泼·轻快",
        "tags": [
            "有声书",
            "少年",
            "男童",
            "5-8岁",
            "自然",
            "活泼",
            "轻快"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "e9b03c9e-586e-4502-a6d2-c78e6ad2ae10",
            "age_stage": "teen",
            "age_min": 5,
            "age_max": 8,
            "accent": "standard-mandarin",
            "group": "男童",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "清脆稚嫩、天真活泼的低龄男童声，适合儿童角色与有声书对白。",
            "vv_style": "男童",
            "vv_tags": [
                "男童",
                "5-8岁"
            ],
            "persona": "[#设定：男声，年龄5-8岁。强制物理级锁定幼态头腔超高尖频基底，浅层薄脆震动发声，彻底屏蔽胸腔低频、厚质闷音、成熟共鸣。声基底细尖清脆，音调高亮稚嫩，咬字短碎天真，气质懵懂活泼，高频脆嫩低龄男童声。]"
        }
    },
    {
        "id": "8bf52ff6-2762-4a40-8f6b-1525de12f927",
        "name": "开朗男孩",
        "language": "zh-CN",
        "gender": "male",
        "style": "活泼·轻快·自然",
        "tags": [
            "有声书",
            "少年",
            "男童",
            "8-11岁",
            "活泼",
            "轻快",
            "自然"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "8bf52ff6-2762-4a40-8f6b-1525de12f927",
            "age_stage": "teen",
            "age_min": 8,
            "age_max": 11,
            "accent": "standard-mandarin",
            "group": "男童",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "敞亮洪亮、咬字利落的男童声，带元气爽朗感，适合有声书中的外向儿童角色。",
            "vv_style": "男童",
            "vv_tags": [
                "男童",
                "8-11岁"
            ],
            "persona": "[#设定：男声，年龄8-11岁。强制物理级锁定大气量开阔外放基底，舒展浅胸腔共鸣，彻底屏蔽小气拘束、细弱音量、拘谨窄频。声基底敞亮洪亮，声压充足外放，咬字大方利落，气质元气爽朗，外向活泼大嗓男童嗓。]"
        }
    },
    {
        "id": "c6d24980-86cc-48a0-9770-ed3e5a57defb",
        "name": "灵动男孩",
        "language": "zh-CN",
        "gender": "male",
        "style": "活泼·轻快·搞笑",
        "tags": [
            "有声书",
            "少年",
            "男童",
            "7-11岁",
            "活泼",
            "轻快",
            "搞笑"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "c6d24980-86cc-48a0-9770-ed3e5a57defb",
            "age_stage": "teen",
            "age_min": 7,
            "age_max": 11,
            "accent": "standard-mandarin",
            "group": "男童",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "短促轻快、节奏跳脱的男童声，带机灵俏皮感，适合有声书中的调皮儿童角色。",
            "vv_style": "男童",
            "vv_tags": [
                "男童",
                "7-11岁"
            ],
            "persona": "[#设定：男声，年龄7-11岁。强制物理级锁定短音节碎脆基底，跳跃式快节奏发声，彻底屏蔽长腔拖音、慢缓沉调、绵长共鸣。声基底短促轻快，节奏跳脱灵动，咬字零碎鲜活，气质调皮好动，机灵俏皮碎音男童嗓。]"
        }
    },
    {
        "id": "22f5a6a3-b8d4-4961-9179-9b4df1ecd8a2",
        "name": "质朴男孩",
        "language": "zh-CN",
        "gender": "male",
        "style": "自然·沉稳·轻快",
        "tags": [
            "有声书",
            "少年",
            "男童",
            "9-12岁",
            "自然",
            "沉稳",
            "轻快"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "22f5a6a3-b8d4-4961-9179-9b4df1ecd8a2",
            "age_stage": "teen",
            "age_min": 9,
            "age_max": 12,
            "accent": "standard-mandarin",
            "group": "男童",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "朴实粗直、随性自然的男童声，带自由原生态感，适合有声书中的乡野儿童角色。",
            "vv_style": "男童",
            "vv_tags": [
                "男童",
                "9-12岁"
            ],
            "persona": "[#设定：男声，年龄9-12岁。强制物理级锁定原生态天然平声基底，无修饰旷野直白发声，彻底屏蔽精致细嗓、刻意软萌、修饰共鸣。声基底朴实粗直，音色原生无雕琢，咬字随性自然，气质自由野性，乡土质朴原生态男童嗓。]"
        }
    },
    {
        "id": "02e8a983-7b21-45ce-80fb-829076d7996a",
        "name": "清脆童声",
        "language": "zh-CN",
        "gender": "male",
        "style": "轻快·活泼·自然",
        "tags": [
            "有声书",
            "少年",
            "男童",
            "5-8岁",
            "轻快",
            "活泼",
            "自然"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "02e8a983-7b21-45ce-80fb-829076d7996a",
            "age_stage": "teen",
            "age_min": 5,
            "age_max": 8,
            "accent": "standard-mandarin",
            "group": "男童",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "纤细透亮、清脆利落的男童声，音调明亮偏高，适合有声书中的低龄男孩角色。",
            "vv_style": "男童",
            "vv_tags": [
                "男童",
                "5-8岁"
            ],
            "persona": "[#设定：男声，年龄5-8岁。强制物理级锁定纯头腔高位震动基底，窄带高频集中发声，彻底屏蔽胸腔共鸣、低频厚度、气声虚化。声底纤细透亮、音调偏高，咬字轻短利落，音色干净直白，典型高亮清脆小男孩原声。]"
        }
    },
    {
        "id": "12cc2017-f012-4887-a8c8-7628ad57d56a",
        "name": "早熟男孩",
        "language": "zh-CN",
        "gender": "male",
        "style": "沉稳·自然·磁性",
        "tags": [
            "有声书",
            "少年",
            "男童",
            "8-12岁",
            "沉稳",
            "自然",
            "磁性"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "12cc2017-f012-4887-a8c8-7628ad57d56a",
            "age_stage": "teen",
            "age_min": 8,
            "age_max": 12,
            "accent": "standard-mandarin",
            "group": "男童",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "厚实偏沉、咬字稳重的男童声，带小大人般的早熟感，适合有声书中的早慧男孩角色。",
            "vv_style": "男童",
            "vv_tags": [
                "男童",
                "8-12岁"
            ],
            "persona": "[#设定：男声，年龄8-12岁。强制物理级锁定浅胸腔低位震动基底，童声低音频段锁死，彻底屏蔽头腔尖音、单薄声线、高亮度音色。声底厚实偏沉、自带小大人质感，发声扎实，咬字沉稳，早熟厚重型低龄男童声。]"
        }
    },
    {
        "id": "2d6052b5-a5ff-4c99-b0fa-bbd79e85e72c",
        "name": "弱声男孩",
        "language": "zh-CN",
        "gender": "male",
        "style": "温柔·感伤·自然",
        "tags": [
            "有声书",
            "少年",
            "男童",
            "5-7岁",
            "温柔",
            "感伤",
            "自然"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "2d6052b5-a5ff-4c99-b0fa-bbd79e85e72c",
            "age_stage": "teen",
            "age_min": 5,
            "age_max": 7,
            "accent": "standard-mandarin",
            "group": "男童",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "虚软轻弱、气息偏重的男童声，带胆小羞怯感，适合有声书中的内向低龄男孩角色。",
            "vv_style": "男童",
            "vv_tags": [
                "男童",
                "5-7岁"
            ],
            "persona": "[#设定：男声，年龄5-7岁。强制物理级锁定呼气主导气声基底，声带轻闭合弱震动，彻底屏蔽实声占比、硬质发力、集中声压。声底虚软发飘、音量细小，全程气息偏重，气质胆小内向，羞怯弱气小男孩声。]"
        }
    },
    {
        "id": "64fabf9f-c1d7-453f-98dc-2d1b499f36b8",
        "name": "朴实男孩",
        "language": "zh-CN",
        "gender": "male",
        "style": "自然·沉稳·热血",
        "tags": [
            "有声书",
            "少年",
            "男童",
            "9-12岁",
            "自然",
            "沉稳",
            "热血"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "64fabf9f-c1d7-453f-98dc-2d1b499f36b8",
            "age_stage": "teen",
            "age_min": 9,
            "age_max": 12,
            "accent": "standard-mandarin",
            "group": "男童",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "粗哑直白、带天然磨砂感的男童声，气质山野硬朗，适合有声书中的朴实儿童角色。",
            "vv_style": "男童",
            "vv_tags": [
                "男童",
                "9-12岁"
            ],
            "persona": "[#设定：男声，年龄9-12岁。强制物理级锁定声带厚层摩擦基底，原生粗沙颗粒质感，彻底屏蔽嫩薄声、纯净亮音、顺滑声壁。声底偏粗偏哑，自带天然磨砂感，发声直白不修饰，山野硬朗朴实男童嗓。]"
        }
    },
    {
        "id": "dd308d7f-1214-431e-b8e0-00465463a088",
        "name": "清爽男孩",
        "language": "zh-CN",
        "gender": "male",
        "style": "自然·轻快·活泼",
        "tags": [
            "有声书",
            "少年",
            "男童",
            "6-9岁",
            "自然",
            "轻快",
            "活泼"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "dd308d7f-1214-431e-b8e0-00465463a088",
            "age_stage": "teen",
            "age_min": 6,
            "age_max": 9,
            "accent": "standard-mandarin",
            "group": "男童",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "轻薄澄澈、咬字清爽的男童声，干净通透，适合有声书中的阳光清爽儿童角色。",
            "vv_style": "男童",
            "vv_tags": [
                "男童",
                "6-9岁"
            ],
            "persona": "[#设定：男声，年龄6-9岁。强制物理级锁定薄层声带紧致震动基底，高通透薄质中频，彻底屏蔽厚重闷感、颗粒沙哑、浑浊共鸣。声底轻薄干净、穿透力柔和，音色澄澈，咬字清爽，干净清爽系男童声。]"
        }
    },
    {
        "id": "9a0ad85f-e0a3-4542-a1df-585039ff07c5",
        "name": "压抑男孩",
        "language": "zh-CN",
        "gender": "male",
        "style": "感伤·沉稳·高冷",
        "tags": [
            "有声书",
            "少年",
            "男童",
            "7-11岁",
            "感伤",
            "沉稳",
            "高冷"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "9a0ad85f-e0a3-4542-a1df-585039ff07c5",
            "age_stage": "teen",
            "age_min": 7,
            "age_max": 11,
            "accent": "standard-mandarin",
            "group": "男童",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "暗闷内收、语速偏慢的男童声，带孤僻压抑感，适合有声书中的低沉儿童角色。",
            "vv_style": "男童",
            "vv_tags": [
                "男童",
                "7-11岁"
            ],
            "persona": "[#设定：男声，年龄7-11岁。强制物理级锁定喉位抬高内缩基底，声道挤压封闭式发声，彻底屏蔽放松开口、开阔共鸣、外放音色。声底偏暗发闷、声音收在喉咙，语速偏慢，情绪低沉，孤僻压抑型男童音。]"
        }
    },
    {
        "id": "f14e2510-d5a2-4fd6-8ac3-0f4dcd6ceed6",
        "name": "冷淡男孩",
        "language": "zh-CN",
        "gender": "male",
        "style": "高冷·沉稳·自然",
        "tags": [
            "有声书",
            "少年",
            "男童",
            "6-10岁",
            "高冷",
            "沉稳",
            "自然"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "f14e2510-d5a2-4fd6-8ac3-0f4dcd6ceed6",
            "age_stage": "teen",
            "age_min": 6,
            "age_max": 10,
            "accent": "standard-mandarin",
            "group": "男童",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "平板生硬、起伏较少的男童声，带木讷冷淡感，适合有声书中的寡言儿童角色。",
            "vv_style": "男童",
            "vv_tags": [
                "男童",
                "6-10岁"
            ],
            "persona": "[#设定：男声，年龄6-10岁。强制物理级锁定口腔扁平横向发声基底，无起伏单一直频，彻底闭锁圆润口腔、婉转尾音、音色层次。声底平板单调、无起伏无奶感，咬字生硬平直，木讷寡言冷淡男童嗓。]"
        }
    },
    {
        "id": "bb9811c8-86e7-4067-9a50-0958e6f6d343",
        "name": "奶润男孩",
        "language": "zh-CN",
        "gender": "male",
        "style": "甜美·温柔·自然",
        "tags": [
            "有声书",
            "少年",
            "男童",
            "5-8岁",
            "甜美",
            "温柔",
            "自然"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "bb9811c8-86e7-4067-9a50-0958e6f6d343",
            "age_stage": "teen",
            "age_min": 5,
            "age_max": 8,
            "accent": "standard-mandarin",
            "group": "男童",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "圆润软糯、奶感充足的男童声，乖巧温顺，适合有声书中的低龄可爱男孩角色。",
            "vv_style": "男童",
            "vv_tags": [
                "男童",
                "5-8岁"
            ],
            "persona": "[#设定：男声，年龄5-8岁。强制物理级锁定口腔圆形聚拢共鸣基底，圆润饱满幼龄频段，彻底屏蔽扁窄发声、尖锐边角、干涩声壁。声底圆润奶感充足，音色柔和软糯，天生乖巧温顺，低龄奶润小男孩声。]"
        }
    },
    {
        "id": "65a95552-50f0-4f5f-93d3-c810b8382d09",
        "name": "气虚男孩",
        "language": "zh-CN",
        "gender": "male",
        "style": "感伤·温柔·自然",
        "tags": [
            "有声书",
            "少年",
            "男童",
            "7-10岁",
            "感伤",
            "温柔",
            "自然"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "65a95552-50f0-4f5f-93d3-c810b8382d09",
            "age_stage": "teen",
            "age_min": 7,
            "age_max": 10,
            "accent": "standard-mandarin",
            "group": "男童",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "断续轻柔、气息不足的男童声，带体弱易碎感，适合有声书中的安静病弱儿童角色。",
            "vv_style": "男童",
            "vv_tags": [
                "男童",
                "7-10岁"
            ],
            "persona": "[#设定：男声，年龄7-10岁。强制物理级锁定呼吸不稳断续震动基底，短节奏弱闭合发声，彻底屏蔽连贯长音、强气息、紧实声质。声底断断续续、气息不足，发声费力轻柔，体弱安静易碎型男童音。]"
        }
    },
    {
        "id": "29c3edb6-008c-4af6-a140-6df23cc12473",
        "name": "孤僻男孩",
        "language": "zh-CN",
        "gender": "male",
        "style": "高冷·沉稳·自然",
        "tags": [
            "有声书",
            "少年",
            "男童",
            "8-12岁",
            "高冷",
            "沉稳",
            "自然"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "29c3edb6-008c-4af6-a140-6df23cc12473",
            "age_stage": "teen",
            "age_min": 8,
            "age_max": 12,
            "accent": "standard-mandarin",
            "group": "男童",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "干瘦清冷、话少声轻的男童声，带极简孤僻感，适合有声书中的疏离儿童角色。",
            "vv_style": "男童",
            "vv_tags": [
                "男童",
                "8-12岁"
            ],
            "persona": "[#设定：男声，年龄8-12岁。强制物理级锁定干燥薄质声带基底，无湿润共鸣冷调中频，彻底屏蔽暖厚底色、湿润雾感、柔和混响。声底干瘦发冷、音色极简寡淡，话少声轻，清冷孤僻薄质男童嗓。]"
        }
    },
    {
        "id": "8bce275a-9bf7-43d3-b4fb-81ce6398ce80",
        "name": "胆怯男孩",
        "language": "zh-CN",
        "gender": "male",
        "style": "感伤·温柔·自然",
        "tags": [
            "有声书",
            "少年",
            "男童",
            "5-7岁",
            "感伤",
            "温柔",
            "自然"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "8bce275a-9bf7-43d3-b4fb-81ce6398ce80",
            "age_stage": "teen",
            "age_min": 5,
            "age_max": 7,
            "accent": "standard-mandarin",
            "group": "男童",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "细紧局促、声音偏小的男童声，带敏感怕生感，适合有声书中的胆怯低龄男孩角色。",
            "vv_style": "男童",
            "vv_tags": [
                "男童",
                "5-7岁"
            ],
            "persona": "[#设定：男声，年龄5-7岁。强制物理级锁定声道窄缩挤压基底，狭小受限震动区间，彻底屏蔽舒展发声、宽频共鸣、放松喉肌。声底又细又紧、声音挤在鼻腔，局促胆怯，敏感怕生，窄压细弱男童音。]"
        }
    },
    {
        "id": "6f365c27-03ae-4a38-8ea9-7fd7598c2939",
        "name": "安静男孩",
        "language": "zh-CN",
        "gender": "male",
        "style": "温柔·慵懒·自然",
        "tags": [
            "有声书",
            "少年",
            "男童",
            "6-9岁",
            "温柔",
            "慵懒",
            "自然"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "6f365c27-03ae-4a38-8ea9-7fd7598c2939",
            "age_stage": "teen",
            "age_min": 6,
            "age_max": 9,
            "accent": "standard-mandarin",
            "group": "男童",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "朦胧湿润、柔和昏暗的男童声，带安静梦幻感，适合有声书中的氛围型儿童角色。",
            "vv_style": "男童",
            "vv_tags": [
                "男童",
                "6-9岁"
            ],
            "persona": "[#设定：男声，年龄6-9岁。强制物理级锁定湿润雾化混声基底，水气包裹柔化震动，彻底屏蔽干硬实声、尖锐高频、干净直白。声底朦胧湿润、自带柔雾滤镜，音色昏暗柔和，安静梦幻氛围感男童嗓。]"
        }
    },
    {
        "id": "a2c9fe37-3950-427a-8eaf-6f75a05969c5",
        "name": "英气男孩",
        "language": "zh-CN",
        "gender": "male",
        "style": "热血·活泼·沉稳",
        "tags": [
            "有声书",
            "少年",
            "男童",
            "9-12岁",
            "热血",
            "活泼",
            "沉稳"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "a2c9fe37-3950-427a-8eaf-6f75a05969c5",
            "age_stage": "teen",
            "age_min": 9,
            "age_max": 12,
            "accent": "standard-mandarin",
            "group": "男童",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "硬朗扎实、咬字有力的男童声，带运动系英气感，适合有声书中的勇敢少年角色。",
            "vv_style": "男童",
            "vv_tags": [
                "男童",
                "9-12岁"
            ],
            "persona": "[#设定：男声，年龄9-12岁。强制物理级锁定声带强闭合硬质发力基底，高硬度实声输出，彻底屏蔽软塌松弛、气声掺混、柔弱震动。声底硬朗扎实、咬字有力，语气倔强硬朗，运动系英气小男孩声。]"
        }
    },
    {
        "id": "181a84dd-28c2-4877-a71c-a056251d2fff",
        "name": "活泼男孩",
        "language": "zh-CN",
        "gender": "male",
        "style": "活泼·轻快·搞笑",
        "tags": [
            "有声书",
            "少年",
            "男童",
            "7-11岁",
            "活泼",
            "轻快",
            "搞笑"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "181a84dd-28c2-4877-a71c-a056251d2fff",
            "age_stage": "teen",
            "age_min": 7,
            "age_max": 11,
            "accent": "standard-mandarin",
            "group": "男童",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "短促轻快、反应灵活的男童声，带调皮活泼感，适合有声书中的好动儿童角色。",
            "vv_style": "男童",
            "vv_tags": [
                "男童",
                "7-11岁"
            ],
            "persona": "[#设定：男声，年龄7-11岁。强制物理级锁定短音节快速震动基底，高节奏跳跃式发声，彻底屏蔽长拖音、慢速低频、厚重滞感。声底短促轻快、节奏灵活跳跃，语速快反应灵，调皮灵动活泼男童嗓。]"
        }
    },
    {
        "id": "f77d5b15-b2c8-419f-86a4-d41b2b94e050",
        "name": "刻板男孩",
        "language": "zh-CN",
        "gender": "male",
        "style": "高冷·专业·沉稳",
        "tags": [
            "有声书",
            "少年",
            "男童",
            "8-11岁",
            "高冷",
            "专业",
            "沉稳"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "f77d5b15-b2c8-419f-86a4-d41b2b94e050",
            "age_stage": "teen",
            "age_min": 8,
            "age_max": 11,
            "accent": "standard-mandarin",
            "group": "男童",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "平整单调、节奏均匀的男童声，带机械人偶感，适合有声书中的理性或非人儿童角色。",
            "vv_style": "男童",
            "vv_tags": [
                "男童",
                "8-11岁"
            ],
            "persona": "[#设定：男声，年龄8-11岁。强制物理级锁定固定频率单调震动基底，无情绪平直输出，彻底屏蔽声调起伏、童真尾音、自然泛音。声底完全平整、节奏均匀刻板，无感无情绪，人偶式中性机械男童音。]"
        }
    },
    {
        "id": "848e92cd-e7de-4439-9091-427eff98987b",
        "name": "内敛男孩",
        "language": "zh-CN",
        "gender": "male",
        "style": "沉稳·高冷·感伤",
        "tags": [
            "有声书",
            "少年",
            "男童",
            "9-12岁",
            "沉稳",
            "高冷",
            "感伤"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "848e92cd-e7de-4439-9091-427eff98987b",
            "age_stage": "teen",
            "age_min": 9,
            "age_max": 12,
            "accent": "standard-mandarin",
            "group": "男童",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "暗沉厚重、沉默寡言的男童声，带深沉内敛感，适合有声书中的成熟向儿童角色。",
            "vv_style": "男童",
            "vv_tags": [
                "男童",
                "9-12岁"
            ],
            "persona": "[#设定：男声，年龄9-12岁。强制物理级锁定胸腔内收闷沉基底，封闭低频厚震动，彻底屏蔽透亮外放、浅色音色、轻量发声。声底暗沉厚重、自带压抑感，性格沉默寡言，深沉内敛成熟向男童嗓。]"
        }
    },
    {
        "id": "8b52a0b1-3dbd-45d1-a878-e8328dc1e072",
        "name": "野性男孩",
        "language": "zh-CN",
        "gender": "male",
        "style": "自然·热血·轻快",
        "tags": [
            "有声书",
            "少年",
            "男童",
            "8-12岁",
            "自然",
            "热血",
            "轻快"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "8b52a0b1-3dbd-45d1-a878-e8328dc1e072",
            "age_stage": "teen",
            "age_min": 8,
            "age_max": 12,
            "accent": "standard-mandarin",
            "group": "男童",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "直白粗爽、气息自由的男童声，带原生态野性感，适合有声书中的旷野儿童角色。",
            "vv_style": "男童",
            "vv_tags": [
                "男童",
                "8-12岁"
            ],
            "persona": "[#设定：男声，年龄8-12岁。强制物理级锁定无修饰直声基底，野外原生放松发声，彻底屏蔽城市细嗓、刻意软糯、修饰共鸣。声底直白粗爽、不加雕琢，气息自由奔放，野性自然原生态男童音。]"
        }
    },
    {
        "id": "719db5a6-9f50-4860-b597-c9079dff9258",
        "name": "温润少年",
        "language": "zh-CN",
        "gender": "male",
        "style": "温柔·自然·沉稳",
        "tags": [
            "有声书",
            "少年",
            "13-16岁",
            "温柔",
            "自然",
            "沉稳"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "719db5a6-9f50-4860-b597-c9079dff9258",
            "age_stage": "teen",
            "age_min": 13,
            "age_max": 16,
            "accent": "standard-mandarin",
            "group": "少年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "温润醇厚、咬字圆缓的少年男声，斯文谦和，适合有声书中的书生少年角色。",
            "vv_style": "少年",
            "vv_tags": [
                "少年",
                "13-16岁"
            ],
            "persona": "[#设定：男声，年龄13-16岁。强制物理级锁定口腔圆腔聚拢发声基底，圆润饱满柔和中频，彻底屏蔽扁薄刺耳、紧促窄频、干涩发硬。声基底温润醇厚，共鸣柔和饱满，咬字圆缓谦和，气质斯文温柔，温润书生少年原声。]"
        }
    },
    {
        "id": "73631cb3-7748-4dc7-a93e-c24bcb1e1298",
        "name": "热血少年",
        "language": "zh-CN",
        "gender": "male",
        "style": "热血·活泼·轻快",
        "tags": [
            "有声书",
            "少年",
            "14-17岁",
            "热血",
            "活泼",
            "轻快"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "73631cb3-7748-4dc7-a93e-c24bcb1e1298",
            "age_stage": "teen",
            "age_min": 14,
            "age_max": 17,
            "accent": "standard-mandarin",
            "group": "少年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "洪亮敞亮、声压充足的少年男声，豪迈开朗，适合有声书中的元气热血角色。",
            "vv_style": "少年",
            "vv_tags": [
                "少年",
                "14-17岁"
            ],
            "persona": "[#设定：男声，年龄14-17岁。强制物理级锁定大气量舒展外放基底，强浅胸腔开阔共鸣，彻底屏蔽小气拘束、窄腔细弱、内敛压抑。声基底洪亮敞亮，声压充足外放，咬字大方爽朗，气质热血开朗，豪迈元气少年声。]"
        }
    },
    {
        "id": "2abf0231-2475-43f3-8621-5dc71490ca5e",
        "name": "破碎少年",
        "language": "zh-CN",
        "gender": "male",
        "style": "高冷·感伤·沉稳",
        "tags": [
            "有声书",
            "少年",
            "14-17岁",
            "高冷",
            "感伤",
            "沉稳"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "2abf0231-2475-43f3-8621-5dc71490ca5e",
            "age_stage": "teen",
            "age_min": 14,
            "age_max": 17,
            "accent": "standard-mandarin",
            "group": "少年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "清寂含蓄、尾音轻收的少年男声，带清冷疏离和古意，适合有声书中的内敛少年角色。",
            "vv_style": "少年",
            "vv_tags": [
                "少年",
                "14-17岁"
            ],
            "persona": "[#设定：男声，年龄14-17岁。强制物理级锁定咽腔收束低柔基底，浅韵内敛窄频共鸣，彻底屏蔽现代直白粗嗓、高调亮音、外放张力。声基底清寂含蓄，尾音轻收不扬，咬字克制舒缓，气质清冷疏离，古意内敛少年声。]"
        }
    },
    {
        "id": "ed86a9c0-a1ed-48f6-b170-a91d19035ed9",
        "name": "温和少年",
        "language": "zh-CN",
        "gender": "male",
        "style": "温柔·自然·沉稳",
        "tags": [
            "有声书",
            "少年",
            "13-16岁",
            "温柔",
            "自然",
            "沉稳"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "ed86a9c0-a1ed-48f6-b170-a91d19035ed9",
            "age_stage": "teen",
            "age_min": 13,
            "age_max": 16,
            "accent": "standard-mandarin",
            "group": "少年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "软润文雅、咬字规整的少年男声，沉静斯文，适合有声书中的书香温和角色。",
            "vv_style": "少年",
            "vv_tags": [
                "少年",
                "13-16岁"
            ],
            "persona": "[#设定：男声，年龄13-16岁。强制物理级锁定软腭轻共鸣文雅基底，低饱和柔和中频，彻底屏蔽粗硬发力、颗粒沙哑、高频尖刺。声基底软润文雅，声线舒展不紧绷，咬字规整轻柔，气质沉静斯文，书香温和少年嗓。]"
        }
    },
    {
        "id": "5bb4c720-77a6-4442-8278-307ca2efc6b2",
        "name": "旷野少年",
        "language": "zh-CN",
        "gender": "male",
        "style": "自然·轻快·热血",
        "tags": [
            "有声书",
            "少年",
            "15-18岁",
            "自然",
            "轻快",
            "热血"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "5bb4c720-77a6-4442-8278-307ca2efc6b2",
            "age_stage": "teen",
            "age_min": 15,
            "age_max": 18,
            "accent": "standard-mandarin",
            "group": "少年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "直白疏朗、气息开阔的少年男声，松弛洒脱，适合有声书中的旷野自由角色。",
            "vv_style": "少年",
            "vv_tags": [
                "少年",
                "15-18岁"
            ],
            "persona": "[#设定：男声，年龄15-18岁。强制物理级锁定全开式野阔发声基底，无修饰原生宽腔，彻底屏蔽精致收束、刻意柔化、狭小共鸣。声基底直白疏朗，气息开阔自由，咬字随性洒脱，气质松弛野性，原生态旷野少年声线。]"
        }
    },
    {
        "id": "be289473-ce94-4d37-8e2b-fdfc7a0e3e81",
        "name": "端正少年",
        "language": "zh-CN",
        "gender": "male",
        "style": "专业·沉稳·自然",
        "tags": [
            "有声书",
            "少年",
            "13-16岁",
            "专业",
            "沉稳",
            "自然"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "be289473-ce94-4d37-8e2b-fdfc7a0e3e81",
            "age_stage": "teen",
            "age_min": 13,
            "age_max": 16,
            "accent": "standard-mandarin",
            "group": "少年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "端正谦和、咬字匀称的少年男声，干净清雅，适合有声书中的儒雅少年角色。",
            "vv_style": "少年",
            "vv_tags": [
                "少年",
                "13-16岁"
            ],
            "persona": "[#设定：男声，年龄13-16岁。强制物理级锁定中腔中正平稳基底，均衡低起伏雅韵频段，彻底屏蔽偏激高亮、沉闷厚浊、浮躁快语。声基底端正谦和，音色干净雅致，咬字匀称舒缓，气质端方清雅，儒门端正少年嗓。]"
        }
    },
    {
        "id": "99b31f0f-ad94-45b3-a66e-2ab692a8920e",
        "name": "沉默少年",
        "language": "zh-CN",
        "gender": "male",
        "style": "沉稳·高冷·自然",
        "tags": [
            "有声书",
            "少年",
            "15-18岁",
            "沉稳",
            "高冷",
            "自然"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "99b31f0f-ad94-45b3-a66e-2ab692a8920e",
            "age_stage": "teen",
            "age_min": 15,
            "age_max": 18,
            "accent": "standard-mandarin",
            "group": "少年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "干涩木沉、咬字沉缓的少年男声，带质朴沉默感，适合有声书中的厚重少年角色。",
            "vv_style": "少年",
            "vv_tags": [
                "少年",
                "15-18岁"
            ],
            "persona": "[#设定：男声，年龄15-18岁。强制物理级锁定咽喉硬质木质基底，无润感干涩厚震，彻底屏蔽湿润雾感、柔和混响、软糯声质。声基底干涩木沉，声线生硬厚重，咬字沉缓笨拙，气质沉默质朴，木系沉厚少年声。]"
        }
    },
    {
        "id": "02bdfbed-072f-41e5-a107-739eef1ca626",
        "name": "正气少年",
        "language": "zh-CN",
        "gender": "male",
        "style": "热血·沉稳·专业",
        "tags": [
            "有声书",
            "少年",
            "14-18岁",
            "热血",
            "沉稳",
            "专业"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "02bdfbed-072f-41e5-a107-739eef1ca626",
            "age_stage": "teen",
            "age_min": 14,
            "age_max": 18,
            "accent": "standard-mandarin",
            "group": "少年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "明朗宏阔、咬字有力的少年男声，坦荡正直，适合有声书中的正气少年角色。",
            "vv_style": "少年",
            "vv_tags": [
                "少年",
                "14-18岁"
            ],
            "persona": "[#设定：男声，年龄14-18岁。强制物理级锁定全腔体贯通开阔基底，大范围均衡共鸣，彻底屏蔽局部窄腔、挤压发声、局促声压。声基底明朗宏阔，音色干净正大，咬字开阔有力，气质坦荡正直，浩然正气少年声线。]"
        }
    },
    {
        "id": "b6579ecd-6797-42e7-bc21-16f15beea03e",
        "name": "温顺少年",
        "language": "zh-CN",
        "gender": "male",
        "style": "温柔·沉稳·自然",
        "tags": [
            "有声书",
            "少年",
            "15-18岁",
            "温柔",
            "沉稳",
            "自然"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "b6579ecd-6797-42e7-bc21-16f15beea03e",
            "age_stage": "teen",
            "age_min": 15,
            "age_max": 18,
            "accent": "standard-mandarin",
            "group": "少年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "松软敦厚、咬字缓慢的少年男声，老实温顺，适合有声书中的质朴可靠角色。",
            "vv_style": "少年",
            "vv_tags": [
                "少年",
                "15-18岁"
            ],
            "persona": "[#设定：男声，年龄15-18岁。强制物理级锁定低频松软厚质基底，低张力平缓震动，彻底屏蔽紧绷发力、锐利咬字、急促语速。声基底松软敦厚，音色温和偏沉，咬字缓慢朴实，气质老实温顺，敦厚慢语少年音。]"
        }
    },
    {
        "id": "f86422ea-0001-4719-a170-3f9afda47165",
        "name": "野性少年",
        "language": "zh-CN",
        "gender": "male",
        "style": "热血·自然·沉稳",
        "tags": [
            "有声书",
            "少年",
            "14-18岁",
            "热血",
            "自然",
            "沉稳"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "f86422ea-0001-4719-a170-3f9afda47165",
            "age_stage": "teen",
            "age_min": 14,
            "age_max": 18,
            "accent": "standard-mandarin",
            "group": "少年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "粗砺扎实、咬字直白的少年男声，自由不羁，适合有声书中的荒野野性角色。",
            "vv_style": "少年",
            "vv_tags": [
                "少年",
                "14-18岁"
            ],
            "persona": "[#设定：男声，年龄14-18岁。强制物理级锁定原生粗砺声带基底，天然磨砂厚震，彻底屏蔽精细修饰、薄嫩声线、规整咬字。声基底粗旷原生，声线扎实硬朗，咬字直白粗犷，气质自由不羁，荒野野性少年声线。]"
        }
    },
    {
        "id": "bfa6d319-7b70-451f-9564-e4cca0740971",
        "name": "古风少年",
        "language": "zh-CN",
        "gender": "male",
        "style": "自然·温柔·沉稳",
        "tags": [
            "有声书",
            "少年",
            "14-17岁",
            "自然",
            "温柔",
            "沉稳"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "bfa6d319-7b70-451f-9564-e4cca0740971",
            "age_stage": "teen",
            "age_min": 14,
            "age_max": 17,
            "accent": "standard-mandarin",
            "group": "少年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "清朗含蓄、温润雅致的少年声线，带轻微古风韵味，适合有声书少年角色。",
            "vv_style": "少年",
            "vv_tags": [
                "少年",
                "14-17岁"
            ],
            "persona": "[#设定：男声，年龄14-17岁。强制物理级锁定古风折腔文雅基底，婉转收韵平缓发声，彻底屏蔽现代直白粗嗓、浮躁节奏、生硬短音。声基底雅致含韵，尾音内敛绵长，咬字端雅舒缓，气质温文清贵，古风世家少年声。]"
        }
    },
    {
        "id": "03f864c9-50f8-46a5-9e93-56e0a00001fe",
        "name": "素淡少年",
        "language": "zh-CN",
        "gender": "male",
        "style": "高冷·沉稳·自然",
        "tags": [
            "有声书",
            "少年",
            "12-14岁",
            "高冷",
            "沉稳",
            "自然"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "03f864c9-50f8-46a5-9e93-56e0a00001fe",
            "age_stage": "teen",
            "age_min": 12,
            "age_max": 14,
            "accent": "standard-mandarin",
            "group": "少年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "素净清淡、起伏克制的少年男声，安静清冷，适合有声书中的佛系寡欲角色。",
            "vv_style": "少年",
            "vv_tags": [
                "少年",
                "12-14岁"
            ],
            "persona": "[#设定：男声，年龄12-14岁。强制物理级锁定极简清寂无色声底，低起伏素净中频，彻底屏蔽浓烈声质、厚重共鸣、外放高亮。声基底素净清淡，音色极简克制，咬字轻敛平缓，气质安静佛系，清冷寡欲少年原声。]"
        }
    },
    {
        "id": "181086bb-5612-444b-9f35-3d6a2e60659e",
        "name": "柔韵书生",
        "language": "zh-CN",
        "gender": "male",
        "style": "温柔·沉稳·自然",
        "tags": [
            "有声书",
            "少年",
            "13-16岁",
            "温柔",
            "沉稳",
            "自然"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "181086bb-5612-444b-9f35-3d6a2e60659e",
            "age_stage": "teen",
            "age_min": 13,
            "age_max": 16,
            "accent": "standard-mandarin",
            "group": "少年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "文雅柔缓、尾音含韵的少年男声，斯文儒雅，适合有声书中的书香少年角色。",
            "vv_style": "少年",
            "vv_tags": [
                "少年",
                "13-16岁"
            ],
            "persona": "[#设定：男声，年龄13-16岁。强制物理级锁定书卷柔韵平缓基底，低起伏文雅中频，彻底屏蔽粗狂外放、尖锐刺耳、浮躁声质。声基底文雅柔缓，尾音内敛含韵，咬字规整温和，气质斯文儒雅，书香温润少年嗓。]"
        }
    },
    {
        "id": "e6115483-8201-4caa-b901-8f04ad49a311",
        "name": "包容少年",
        "language": "zh-CN",
        "gender": "male",
        "style": "温柔·沉稳·自然",
        "tags": [
            "有声书",
            "少年",
            "15-18岁",
            "温柔",
            "沉稳",
            "自然"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "e6115483-8201-4caa-b901-8f04ad49a311",
            "age_stage": "teen",
            "age_min": 15,
            "age_max": 18,
            "accent": "standard-mandarin",
            "group": "少年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "暖厚温润、咬字舒缓的少年男声，包容可靠，适合有声书中的治愈陪伴角色。",
            "vv_style": "少年",
            "vv_tags": [
                "少年",
                "15-18岁"
            ],
            "persona": "[#设定：男声，年龄15-18岁。强制物理级锁定暖调厚质包裹基底，柔和胸腔暖共鸣，彻底屏蔽冷薄尖声、干涩沙哑、冷冽声底。声基底暖厚温润，音色柔和厚重，咬字沉稳舒缓，气质包容可靠，暖厚治愈少年声线。]"
        }
    },
    {
        "id": "92bf7318-93ff-4264-a748-39e33a6da052",
        "name": "妖媚少年",
        "language": "zh-CN",
        "gender": "male",
        "style": "性感·腹黑·高冷",
        "tags": [
            "有声书",
            "少年",
            "14-17岁",
            "性感",
            "腹黑",
            "高冷"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "92bf7318-93ff-4264-a748-39e33a6da052",
            "age_stage": "teen",
            "age_min": 14,
            "age_max": 17,
            "accent": "standard-mandarin",
            "group": "少年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "阴柔婉转、咬字轻缓的少年男声，带邪魅神秘感，适合有声书中的诡魅少年角色。",
            "vv_style": "少年",
            "vv_tags": [
                "少年",
                "14-17岁"
            ],
            "persona": "[#设定：男声，年龄14-17岁。强制物理级锁定柔诡婉转偏柔基底，阴柔缠绕气息，彻底屏蔽阳刚粗硬、直白硬朗、外放正气。声基底柔婉带诡，音色阴柔婉转，咬字轻缓勾调，气质邪魅神秘，诡魅阴柔少年音。]"
        }
    },
    {
        "id": "5be1b085-829b-4d38-852e-ca1c05742ec7",
        "name": "厚重少年",
        "language": "zh-CN",
        "gender": "male",
        "style": "沉稳·磁性·自然",
        "tags": [
            "有声书",
            "少年",
            "15-18岁",
            "沉稳",
            "磁性",
            "自然"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "5be1b085-829b-4d38-852e-ca1c05742ec7",
            "age_stage": "teen",
            "age_min": 15,
            "age_max": 18,
            "accent": "standard-mandarin",
            "group": "少年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "钝厚低沉、咬字缓慢的少年男声，憨厚朴实，适合有声书中的沉稳迟缓角色。",
            "vv_style": "少年",
            "vv_tags": [
                "少年",
                "15-18岁"
            ],
            "persona": "[#设定：男声，年龄15-18岁。强制物理级锁定钝感厚实低沉基底，无锐度厚重声质，彻底屏蔽尖锐灵敏、清亮薄声、利落锐感。声基底钝厚沉稳，音色浑浊厚实，咬字缓慢木讷，气质憨厚迟缓，钝感朴实少年声。]"
        }
    },
    {
        "id": "fd195a2c-7cb0-4d53-9b3b-0903c3d6a7d0",
        "name": "豪放少年",
        "language": "zh-CN",
        "gender": "male",
        "style": "热血·活泼·自然",
        "tags": [
            "有声书",
            "少年",
            "15-18岁",
            "热血",
            "活泼",
            "自然"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "fd195a2c-7cb0-4d53-9b3b-0903c3d6a7d0",
            "age_stage": "teen",
            "age_min": 15,
            "age_max": 18,
            "accent": "standard-mandarin",
            "group": "少年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "洪亮开阔、咬字洒脱的少年男声，爽朗野性，适合有声书中的旷野豪放角色。",
            "vv_style": "少年",
            "vv_tags": [
                "少年",
                "15-18岁"
            ],
            "persona": "[#设定：男声，年龄15-18岁。强制物理级锁定旷野开阔外放基底，大气量舒展发声，彻底屏蔽拘束内敛、细弱窄嗓、紧绷压抑。声基底洪亮豪放，声量开阔舒展，咬字洒脱直白，气质爽朗野性，旷野豪放少年声线。]"
        }
    },
    {
        "id": "b72a8601-2655-45d2-86f4-b69e4359e60f",
        "name": "儒雅少年",
        "language": "zh-CN",
        "gender": "male",
        "style": "温柔·沉稳·自然",
        "tags": [
            "有声书",
            "少年",
            "13-16岁",
            "温柔",
            "沉稳",
            "自然"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "b72a8601-2655-45d2-86f4-b69e4359e60f",
            "age_stage": "teen",
            "age_min": 13,
            "age_max": 16,
            "accent": "standard-mandarin",
            "group": "少年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "温润含蓄、咬字端和的少年男声，清雅谦和，适合有声书中的古风儒雅角色。",
            "vv_style": "少年",
            "vv_tags": [
                "少年",
                "13-16岁"
            ],
            "persona": "[#设定：男声，年龄13-16岁。强制物理级锁定儒风收韵平缓基底，低起伏雅致内收声段，彻底屏蔽粗狂直白、尖锐炸音、浮躁节奏。声基底儒雅敛韵，音色温润含蓄，咬字端和舒缓，气质清雅谦和，古风儒韵少年嗓。]"
        }
    },
    {
        "id": "750af77b-d94c-4548-b44c-caa144daafdb",
        "name": "木纳少年",
        "language": "zh-CN",
        "gender": "male",
        "style": "沉稳·自然·高冷",
        "tags": [
            "有声书",
            "少年",
            "15-18岁",
            "沉稳",
            "自然",
            "高冷"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "750af77b-d94c-4548-b44c-caa144daafdb",
            "age_stage": "teen",
            "age_min": 15,
            "age_max": 18,
            "accent": "standard-mandarin",
            "group": "少年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "木沉厚重、咬字迟缓的少年男声，质朴木讷，适合有声书中的迟钝沉默角色。",
            "vv_style": "少年",
            "vv_tags": [
                "少年",
                "15-18岁"
            ],
            "persona": "[#设定：男声，年龄15-18岁。强制物理级锁定木质钝沉厚质基底，无锐度低反应声质，彻底屏蔽灵敏锐感、清亮薄声、轻快节奏。声基底木讷沉厚，音色浑浊迟钝，咬字迟缓笨重，气质质朴木讷，木沉钝厚少年声。]"
        }
    },
    {
        "id": "41b316b4-5744-43eb-987d-3ae0a8c8efcc",
        "name": "洒脱少年",
        "language": "zh-CN",
        "gender": "male",
        "style": "热血·自然·轻快",
        "tags": [
            "有声书",
            "少年",
            "15-18岁",
            "热血",
            "自然",
            "轻快"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "41b316b4-5744-43eb-987d-3ae0a8c8efcc",
            "age_stage": "teen",
            "age_min": 15,
            "age_max": 18,
            "accent": "standard-mandarin",
            "group": "少年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "苍劲粗阔、咬字刚直的少年男声，野性洒脱，适合有声书中的旷野少年角色。",
            "vv_style": "少年",
            "vv_tags": [
                "少年",
                "15-18岁"
            ],
            "persona": "[#设定：男声，年龄15-18岁。强制物理级锁定苍劲野阔外放基底，旷野强震原生发声，彻底屏蔽精致细嗓、内敛拘谨、柔化修饰。声基底苍劲粗阔，声量雄劲舒展，咬字洒脱刚直，气质野性洒脱，苍劲野阔少年声线。]"
        }
    },
    {
        "id": "681960bd-8045-4812-95d1-8ac6f6be2888",
        "name": "浩然少年",
        "language": "zh-CN",
        "gender": "male",
        "style": "热血·沉稳·专业",
        "tags": [
            "有声书",
            "少年",
            "14-18岁",
            "热血",
            "沉稳",
            "专业"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "681960bd-8045-4812-95d1-8ac6f6be2888",
            "age_stage": "teen",
            "age_min": 14,
            "age_max": 18,
            "accent": "standard-mandarin",
            "group": "少年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "宽宏明朗、咬字端正的少年男声，正直爽朗，适合有声书中的浩然正气角色。",
            "vv_style": "少年",
            "vv_tags": [
                "少年",
                "14-18岁"
            ],
            "persona": "[#设定：男声，年龄14-18岁。强制物理级锁定宽腔开阔浩然基底，大范围舒展共鸣，彻底屏蔽窄腔挤压、细弱声线、拘谨发声。声基底宽宏明朗，声量舒展大气，咬字端正开阔，气质正直爽朗，宽腔浩然少年声线。]"
        }
    },
    {
        "id": "7628b7c2-0c51-4048-90c6-ef26be9d997c",
        "name": "清冷少年",
        "language": "zh-CN",
        "gender": "male",
        "style": "高冷·沉稳·自然",
        "tags": [
            "有声书",
            "少年",
            "13-16岁",
            "高冷",
            "沉稳",
            "自然"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "7628b7c2-0c51-4048-90c6-ef26be9d997c",
            "age_stage": "teen",
            "age_min": 13,
            "age_max": 16,
            "accent": "standard-mandarin",
            "group": "少年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "古雅沉敛、尾音收韵的少年男声，清冷贵气，适合有声书中的古风儒雅角色。",
            "vv_style": "少年",
            "vv_tags": [
                "少年",
                "13-16岁"
            ],
            "persona": "[#设定：男声，年龄13-16岁。强制物理级锁定古韵沉雅收腔基底，低缓古韵婉转发声，彻底屏蔽现代直白、浮躁快节奏、粗犷声质。声基底古雅沉敛，尾音收韵绵长，咬字平缓端雅，气质清冷贵气，古韵儒雅少年嗓。]"
        }
    },
    {
        "id": "69570918-966e-46df-89c6-11dc709c65ba",
        "name": "憨厚少年",
        "language": "zh-CN",
        "gender": "male",
        "style": "沉稳·自然·温柔",
        "tags": [
            "有声书",
            "少年",
            "15-18岁",
            "沉稳",
            "自然",
            "温柔"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "69570918-966e-46df-89c6-11dc709c65ba",
            "age_stage": "teen",
            "age_min": 15,
            "age_max": 18,
            "accent": "standard-mandarin",
            "group": "少年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "木讷敦厚、咬字迟缓的少年男声，老实憨厚，适合有声书中的质朴少年角色。",
            "vv_style": "少年",
            "vv_tags": [
                "少年",
                "15-18岁"
            ],
            "persona": "[#设定：男声，年龄15-18岁。强制物理级锁定憨厚木缓钝感基底，低反应厚重平缓声质，彻底屏蔽灵敏锐感、轻快节奏、清亮音色。声基底木讷敦厚，音色浑浊偏沉，咬字迟缓笨拙，气质老实憨厚，憨厚木缓少年音。]"
        }
    },
    {
        "id": "0506e218-a1c7-4320-84e7-0dce54564c69",
        "name": "苍劲少年",
        "language": "zh-CN",
        "gender": "male",
        "style": "热血·自然·沉稳",
        "tags": [
            "有声书",
            "少年",
            "14-18岁",
            "热血",
            "自然",
            "沉稳"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "0506e218-a1c7-4320-84e7-0dce54564c69",
            "age_stage": "teen",
            "age_min": 14,
            "age_max": 18,
            "accent": "standard-mandarin",
            "group": "少年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "苍劲粗旷、咬字洒脱的少年男声，自由野性，适合有声书中的荒野少年角色。",
            "vv_style": "少年",
            "vv_tags": [
                "少年",
                "14-18岁"
            ],
            "persona": "[#设定：男声，年龄14-18岁。强制物理级锁定荒野苍劲粗旷基底，原生态强震外放发声，彻底屏蔽精致修饰、柔化音感、内敛拘束。声基底苍劲粗旷，声线硬朗开阔，咬字洒脱直白，气质自由野性，荒野苍劲少年声线。]"
        }
    },
    {
        "id": "40f6439a-7ef9-42e2-b0ba-dca7ccaf5f15",
        "name": "娇憨少女",
        "language": "zh-CN",
        "gender": "female",
        "style": "甜美·活泼·自然",
        "tags": [
            "有声书",
            "青年",
            "少女",
            "19-23岁",
            "甜美",
            "活泼",
            "自然"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "40f6439a-7ef9-42e2-b0ba-dca7ccaf5f15",
            "age_stage": "young_adult",
            "age_min": 19,
            "age_max": 23,
            "accent": "standard-mandarin",
            "group": "少女",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "懵懂软糯、咬字微缓的青年女声，单纯娇憨，适合有声书中的天然呆少女角色。",
            "vv_style": "少女",
            "vv_tags": [
                "少女",
                "19-23岁"
            ],
            "persona": "[#设定：女声，年龄19-23岁。强制物理级锁定钝感清甜中音，微滞软气息，彻底屏蔽尖锐、精明、冷冽。声线懵懂软糯，咬字微微迟缓，气质单纯娇憨，适合天然呆、呆萌少女角色。]"
        }
    },
    {
        "id": "6f740b00-3dc1-4c3f-9081-b117c17186f6",
        "name": "软萌甜妹",
        "language": "zh-CN",
        "gender": "female",
        "style": "甜美",
        "tags": [
            "有声书",
            "青年",
            "少女",
            "20-24岁",
            "甜美"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "6f740b00-3dc1-4c3f-9081-b117c17186f6",
            "age_stage": "young_adult",
            "age_min": 20,
            "age_max": 24,
            "accent": "standard-mandarin",
            "group": "少女",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "",
            "vv_style": "少女",
            "vv_tags": [
                "少女",
                "20-24岁"
            ],
            "persona": "[#设定：女声，年龄20-24岁。强制物理级锁定软糯奶甜高音，绵柔轻气声，彻底屏蔽粗哑、厚重、冷硬。声线软绵清甜，咬字圆润轻柔，气质乖巧软萌，适合甜系少女、邻家小妹角色。]"
        }
    },
    {
        "id": "7139fcea-0940-49f4-87c8-d879d5513b57",
        "name": "元气少女",
        "language": "zh-CN",
        "gender": "female",
        "style": "活泼·轻快·甜美",
        "tags": [
            "有声书",
            "青年",
            "少女",
            "19-23岁",
            "活泼",
            "轻快",
            "甜美"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "7139fcea-0940-49f4-87c8-d879d5513b57",
            "age_stage": "young_adult",
            "age_min": 19,
            "age_max": 23,
            "accent": "standard-mandarin",
            "group": "少女",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "清亮鲜活、咬字轻快的青年女声，阳光外向，适合有声书中的运动系元气少女角色。",
            "vv_style": "少女",
            "vv_tags": [
                "少女",
                "19-23岁"
            ],
            "persona": "[#设定：女声，年龄19-23岁。强制物理级锁定明亮跳脱高音，强活力气息，彻底屏蔽慵懒、低沉、压抑。声线清亮鲜活，咬字轻快灵动，气质阳光外向，适合运动系、活力少女角色。]"
        }
    },
    {
        "id": "f94b2094-1ccd-44fe-9757-b839bfbc6bea",
        "name": "蜜糯软妹",
        "language": "zh-CN",
        "gender": "female",
        "style": "甜美·温柔·自然",
        "tags": [
            "有声书",
            "青年",
            "少女",
            "20-24岁",
            "甜美",
            "温柔",
            "自然"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "f94b2094-1ccd-44fe-9757-b839bfbc6bea",
            "age_stage": "young_adult",
            "age_min": 20,
            "age_max": 24,
            "accent": "standard-mandarin",
            "group": "少女",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "软糯绵密、咬字黏柔的青年女声，清甜无害，适合有声书中的软萌乖巧角色。",
            "vv_style": "少女",
            "vv_tags": [
                "少女",
                "20-24岁"
            ],
            "persona": "[#设定：女声，年龄20-24岁。强制物理级锁定绵密蜜感柔中音，绵软弱气声，彻底屏蔽冷硬、利落、沉厚。声线软糯绵密，咬字黏柔清甜，气质软甜无害，适合软萌系、乖巧小女生角色。]"
        }
    },
    {
        "id": "7cc8ce54-6dc2-439a-ac77-178074b0de9c",
        "name": "青涩学妹",
        "language": "zh-CN",
        "gender": "female",
        "style": "甜美·自然·温柔",
        "tags": [
            "有声书",
            "青年",
            "少女",
            "18-22岁",
            "甜美",
            "自然",
            "温柔"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "7cc8ce54-6dc2-439a-ac77-178074b0de9c",
            "age_stage": "young_adult",
            "age_min": 18,
            "age_max": 22,
            "accent": "standard-mandarin",
            "group": "少女",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "青涩纯净、咬字略带拘谨的青年女声，懵懂清新，适合有声书中的纯情学妹角色。",
            "vv_style": "少女",
            "vv_tags": [
                "少女",
                "18-22岁"
            ],
            "persona": "[#设定：女声，年龄18-22岁。强制物理级锁定青涩干净偏高音，纯净无修饰气息，彻底屏蔽成熟、沙哑、世故。声线青涩纯净，咬字略带拘谨，气质懵懂青涩，适合新生学妹、纯情少女角色。]"
        }
    },
    {
        "id": "5e371831-0341-440a-9e5c-51736be8927b",
        "name": "甜酷少女",
        "language": "zh-CN",
        "gender": "female",
        "style": "甜美·高冷·轻快",
        "tags": [
            "有声书",
            "青年",
            "少女",
            "20-24岁",
            "甜美",
            "高冷",
            "轻快"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "5e371831-0341-440a-9e5c-51736be8927b",
            "age_stage": "young_adult",
            "age_min": 20,
            "age_max": 24,
            "accent": "standard-mandarin",
            "group": "少女",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "甜冽清爽、咬字利落的青年女声，个性甜酷，适合有声书中的潮流少女角色。",
            "vv_style": "少女",
            "vv_tags": [
                "少女",
                "20-24岁"
            ],
            "persona": "[#设定：女声，年龄20-24岁。强制物理级锁定甜冽平衡中音，脆爽紧实气息，彻底屏蔽纯软、冷硬、甜腻。声线甜冽结合，咬字清爽利落，气质甜酷混搭，适合潮流小众、个性少女角色。]"
        }
    },
    {
        "id": "e12d1d4c-0324-4cf0-abcc-7a0c3bc2afbe",
        "name": "尖细少女",
        "language": "zh-CN",
        "gender": "female",
        "style": "高冷·自然·感伤",
        "tags": [
            "有声书",
            "少年",
            "少女",
            "14-17岁",
            "高冷",
            "自然",
            "感伤"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "e12d1d4c-0324-4cf0-abcc-7a0c3bc2afbe",
            "age_stage": "teen",
            "age_min": 14,
            "age_max": 17,
            "accent": "standard-mandarin",
            "group": "少女",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "高位尖细、穿透力强的少年女声，敏感胆怯，适合有声书中的纤细少女角色。",
            "vv_style": "少女",
            "vv_tags": [
                "少女",
                "14-17岁"
            ],
            "persona": "[#设定：女声，年龄14-17岁。强制物理级锁定头腔高位尖细基底，窄频高频紧绷发声，彻底屏蔽胸腔低音、厚质声、圆润共鸣。声基底纤细尖锐，穿透力极强，咬字轻促紧凑，气质敏感胆怯，高频细尖型少女声线。]"
        }
    },
    {
        "id": "b74f3aa5-5707-47d2-9e87-fef5cd134e91",
        "name": "厚沉少女",
        "language": "zh-CN",
        "gender": "female",
        "style": "沉稳·高冷·磁性",
        "tags": [
            "有声书",
            "少年",
            "少女",
            "15-18岁",
            "沉稳",
            "高冷",
            "磁性"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "b74f3aa5-5707-47d2-9e87-fef5cd134e91",
            "age_stage": "teen",
            "age_min": 15,
            "age_max": 18,
            "accent": "standard-mandarin",
            "group": "少女",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "低沉偏厚、咬字沉实的少年女声，早熟清冷，适合有声书中的稳重少女角色。",
            "vv_style": "少女",
            "vv_tags": [
                "少女",
                "15-18岁"
            ],
            "persona": "[#设定：女声，年龄15-18岁。强制物理级锁定下沉胸腔低位少女基底，低中频高密度实声，彻底屏蔽头腔尖音、单薄细嗓、轻浮气声。声基底低沉偏厚，声压稳重，咬字沉实内敛，气质早熟清冷，低频厚嗓早熟少女音。]"
        }
    },
    {
        "id": "5cfb3c1d-3f9e-45ee-aaa9-2617c98b0db9",
        "name": "虚气少女",
        "language": "zh-CN",
        "gender": "female",
        "style": "感伤·温柔·自然",
        "tags": [
            "有声书",
            "少年",
            "少女",
            "13-16岁",
            "感伤",
            "温柔",
            "自然"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "5cfb3c1d-3f9e-45ee-aaa9-2617c98b0db9",
            "age_stage": "teen",
            "age_min": 13,
            "age_max": 16,
            "accent": "standard-mandarin",
            "group": "少女",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "气声虚软、咬字轻飘的少年女声，柔弱易碎，适合有声书中的病弱少女角色。",
            "vv_style": "少女",
            "vv_tags": [
                "少女",
                "13-16岁"
            ],
            "persona": "[#设定：女声，年龄13-16岁。强制物理级锁定全气声虚化发声基底，口鼻浅息弱震动，彻底屏蔽硬质实声、强声压、立体共鸣。声基底通体虚软，实声占比极低，咬字轻飘带息，气质柔弱易碎，纯气声弱感少女嗓。]"
        }
    },
    {
        "id": "f178fb5d-d9d0-4ba3-b218-2d7b2f367347",
        "name": "哑感少女",
        "language": "zh-CN",
        "gender": "female",
        "style": "高冷·自然·感伤",
        "tags": [
            "有声书",
            "少年",
            "少女",
            "15-18岁",
            "高冷",
            "自然",
            "感伤"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "f178fb5d-d9d0-4ba3-b218-2d7b2f367347",
            "age_stage": "teen",
            "age_min": 15,
            "age_max": 18,
            "accent": "standard-mandarin",
            "group": "少女",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "轻微磨砂颗粒、咬字松弛的少年女声，青涩清冷，适合有声书中的小众少女角色。",
            "vv_style": "少女",
            "vv_tags": [
                "少女",
                "15-18岁"
            ],
            "persona": "[#设定：女声，年龄15-18岁。强制物理级锁定轻微声带摩擦磨砂基底，低龄微哑特殊频段，彻底屏蔽纯甜嫩嗓、高亮净音、顺滑声质。声基底自带细微颗粒哑感，青涩沙哑，咬字随性松弛，气质小众清冷，磨砂颗粒少女声。]"
        }
    },
    {
        "id": "0ecc3473-bafb-4b2e-9cad-f1cc20258b4b",
        "name": "通透少女",
        "language": "zh-CN",
        "gender": "female",
        "style": "活泼·轻快·自然",
        "tags": [
            "有声书",
            "少年",
            "少女",
            "14-17岁",
            "活泼",
            "轻快",
            "自然"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "0ecc3473-bafb-4b2e-9cad-f1cc20258b4b",
            "age_stage": "teen",
            "age_min": 14,
            "age_max": 17,
            "accent": "standard-mandarin",
            "group": "少女",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "清亮通透、咬字轻快的少年女声，阳光干净，适合有声书中的明朗少女角色。",
            "vv_style": "少女",
            "vv_tags": [
                "少女",
                "14-17岁"
            ],
            "persona": "[#设定：女声，年龄14-17岁。强制物理级锁定头腔集中通透发声，高洁净高频泛音，彻底屏蔽闷浊低音、沙哑颗粒、厚重共鸣。声基底清亮剔透，音色干净无瑕，咬字轻快舒展，气质阳光干净，亮腔通透少女基底。]"
        }
    },
    {
        "id": "357c8719-5265-4405-9fa8-b9b840a05257",
        "name": "闷哑少女",
        "language": "zh-CN",
        "gender": "female",
        "style": "感伤·高冷·沉稳",
        "tags": [
            "有声书",
            "少年",
            "少女",
            "16-19岁",
            "感伤",
            "高冷",
            "沉稳"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "357c8719-5265-4405-9fa8-b9b840a05257",
            "age_stage": "teen",
            "age_min": 16,
            "age_max": 19,
            "accent": "standard-mandarin",
            "group": "少女",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "暗沉闷柔、咬字含收的少年女声，孤僻压抑，适合有声书中的内敛少女角色。",
            "vv_style": "少女",
            "vv_tags": [
                "少女",
                "16-19岁"
            ],
            "persona": "[#设定：女声，年龄16-19岁。强制物理级锁定喉位内收闷声基底，封闭压抑中频，彻底屏蔽外放敞亮、高位发声、鲜活音色。声基底暗沉闷柔，音色偏灰，咬字含收缓慢，气质孤僻抑郁，闷压内敛少女声线。]"
        }
    },
    {
        "id": "a7952868-975b-4a4e-ab2d-3a362e1c3353",
        "name": "扁嗓少女",
        "language": "zh-CN",
        "gender": "female",
        "style": "高冷·自然·沉稳",
        "tags": [
            "有声书",
            "少年",
            "少女",
            "13-17岁",
            "高冷",
            "自然",
            "沉稳"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "a7952868-975b-4a4e-ab2d-3a362e1c3353",
            "age_stage": "teen",
            "age_min": 13,
            "age_max": 17,
            "accent": "standard-mandarin",
            "group": "少女",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "扁平直白、咬字生硬的少年女声，木讷冷淡，适合有声书中的寡言少女角色。",
            "vv_style": "少女",
            "vv_tags": [
                "少女",
                "13-17岁"
            ],
            "persona": "[#设定：女声，年龄13-17岁。强制物理级锁定扁平无起伏发声基底，平直无共鸣单频，彻底屏蔽婉转柔腔、立体泛音、情绪起伏。声基底扁平直白，音色寡淡无层次，咬字生硬平缓，气质木讷冷淡，平板无修饰少女嗓。]"
        }
    },
    {
        "id": "e30e203f-6d24-4269-ba1c-f6b2f61814a4",
        "name": "软润少女",
        "language": "zh-CN",
        "gender": "female",
        "style": "温柔·甜美·自然",
        "tags": [
            "有声书",
            "少年",
            "少女",
            "14-18岁",
            "温柔",
            "甜美",
            "自然"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "e30e203f-6d24-4269-ba1c-f6b2f61814a4",
            "age_stage": "teen",
            "age_min": 14,
            "age_max": 18,
            "accent": "standard-mandarin",
            "group": "少女",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "软糯圆润、咬字圆缓的少年女声，甜美温顺，适合有声书中的乖巧少女角色。",
            "vv_style": "少女",
            "vv_tags": [
                "少女",
                "14-18岁"
            ],
            "persona": "[#设定：女声，年龄14-18岁。强制物理级锁定口腔圆腔包裹发声，圆润饱满中频基底，彻底屏蔽扁薄声、尖锐窄频、干涩质感。声基底软糯圆润，柔和共鸣饱满，咬字圆缓乖巧，气质甜美温顺，圆腔柔润少女原声。]"
        }
    },
    {
        "id": "6d655e4f-5154-4f0f-a0da-953082292d40",
        "name": "青涩少女",
        "language": "zh-CN",
        "gender": "female",
        "style": "感伤·自然·温柔",
        "tags": [
            "有声书",
            "少年",
            "少女",
            "15-19岁",
            "感伤",
            "自然",
            "温柔"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "6d655e4f-5154-4f0f-a0da-953082292d40",
            "age_stage": "teen",
            "age_min": 15,
            "age_max": 19,
            "accent": "standard-mandarin",
            "group": "少女",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "松垮微裂、咬字断续的少年女声，懵懂青涩，适合有声书中的变声期少女角色。",
            "vv_style": "少女",
            "vv_tags": [
                "少女",
                "15-19岁"
            ],
            "persona": "[#设定：女声，年龄15-19岁。强制物理级锁定变声期断续破碎基底，声线断层微裂频段，彻底屏蔽完整紧实声、纯嫩声质、稳定共鸣。声基底松垮青涩，轻微断音不稳，咬字费力断续，气质懵懂青涩，变声期破碎少女音。]"
        }
    },
    {
        "id": "80823acc-487f-43c4-854b-13fc3a1bc753",
        "name": "冷感少女",
        "language": "zh-CN",
        "gender": "female",
        "style": "高冷·沉稳·自然",
        "tags": [
            "有声书",
            "少年",
            "少女",
            "14-17岁",
            "高冷",
            "沉稳",
            "自然"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "80823acc-487f-43c4-854b-13fc3a1bc753",
            "age_stage": "teen",
            "age_min": 14,
            "age_max": 17,
            "accent": "standard-mandarin",
            "group": "少女",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "单薄冷调、咬字收敛的少年女声，疏离寡言，适合有声书中的清冷少女角色。",
            "vv_style": "少女",
            "vv_tags": [
                "少女",
                "14-17岁"
            ],
            "persona": "[#设定：女声，年龄14-17岁。强制物理级锁定超薄冷调声基，无共鸣干冷单音，彻底屏蔽厚润暖底、绵柔共鸣、饱满声压。声基底单薄发冷，色素净偏冷，咬字短浅收敛，气质疏离寡言，薄冷禁欲系少女嗓。]"
        }
    },
    {
        "id": "47e9d9b2-5693-4818-815c-7d0beb3c0151",
        "name": "洪量少女",
        "language": "zh-CN",
        "gender": "female",
        "style": "活泼·轻快·热血",
        "tags": [
            "有声书",
            "少年",
            "少女",
            "15-18岁",
            "活泼",
            "轻快",
            "热血"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "47e9d9b2-5693-4818-815c-7d0beb3c0151",
            "age_stage": "teen",
            "age_min": 15,
            "age_max": 18,
            "accent": "standard-mandarin",
            "group": "少女",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "洪亮敞亮、咬字外放的少年女声，爽朗大方，适合有声书中的开朗少女角色。",
            "vv_style": "少女",
            "vv_tags": [
                "少女",
                "15-18岁"
            ],
            "persona": "[#设定：女声，年龄15-18岁。强制物理级锁定大气量开阔发声基底，强舒展外放共鸣，彻底屏蔽小气弱息、拘束窄嗓、纤细声线。声基底洪亮敞亮，声压充足，咬字大方外放，气质爽朗大方，大嗓开朗少女声。]"
        }
    },
    {
        "id": "d52f0461-016e-47bd-aed5-ee174a8cc9bb",
        "name": "细弱少女",
        "language": "zh-CN",
        "gender": "female",
        "style": "温柔·感伤·自然",
        "tags": [
            "有声书",
            "少年",
            "少女",
            "13-16岁",
            "温柔",
            "感伤",
            "自然"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "d52f0461-016e-47bd-aed5-ee174a8cc9bb",
            "age_stage": "teen",
            "age_min": 13,
            "age_max": 16,
            "accent": "standard-mandarin",
            "group": "少女",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "纤细微弱、咬字细缓的少年女声，内向胆怯，适合有声书中的柔弱少女角色。",
            "vv_style": "少女",
            "vv_tags": [
                "少女",
                "13-16岁"
            ],
            "persona": "[#设定：女声，年龄13-16岁。强制物理级锁定狭腔窄幅收缩基底，细弱窄频低音量，彻底屏蔽开阔大嗓、洪亮外放、硬质发声。声基底纤细局促，音量微弱，咬字细缓胆怯，气质内向社恐，狭腔柔弱少女基底。]"
        }
    },
    {
        "id": "41a1f9e8-fd1e-4676-9669-dc79445000da",
        "name": "雾感少女",
        "language": "zh-CN",
        "gender": "female",
        "style": "温柔·慵懒·自然",
        "tags": [
            "有声书",
            "少年",
            "少女",
            "14-18岁",
            "温柔",
            "慵懒",
            "自然"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "41a1f9e8-fd1e-4676-9669-dc79445000da",
            "age_stage": "teen",
            "age_min": 14,
            "age_max": 18,
            "accent": "standard-mandarin",
            "group": "少女",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "朦胧柔和、咬字轻缓的少年女声，氛围柔糯，适合有声书中的神秘少女角色。",
            "vv_style": "少女",
            "vv_tags": [
                "少女",
                "14-18岁"
            ],
            "persona": "[#设定：女声，年龄14-18岁。强制物理级锁定雾感柔混朦胧基底，半柔半虚特殊混音频段，彻底屏蔽直白实声、尖锐亮音、干净声质。声基底朦胧柔和，层次浑浊软糯，咬字模糊轻缓，气质氛围感十足，雾混朦胧少女嗓。]"
        }
    },
    {
        "id": "ab480ba1-292d-4473-b2d5-4dd5467e37cc",
        "name": "力量少女",
        "language": "zh-CN",
        "gender": "female",
        "style": "热血·活泼·自然",
        "tags": [
            "有声书",
            "少年",
            "少女",
            "15-19岁",
            "热血",
            "活泼",
            "自然"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "ab480ba1-292d-4473-b2d5-4dd5467e37cc",
            "age_stage": "teen",
            "age_min": 15,
            "age_max": 19,
            "accent": "standard-mandarin",
            "group": "少女",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "刚硬利落、咬字有力的少年女声，英气倔强，适合有声书中的硬核少女角色。",
            "vv_style": "少女",
            "vv_tags": [
                "少女",
                "15-19岁"
            ],
            "persona": "[#设定：女声，年龄15-19岁。强制物理级锁定紧绷硬质发力基底，高硬度紧实实声，彻底屏蔽软绵松弛、娇柔气声、松散共鸣。声基底刚硬利落，发力感清晰，咬字干脆有力，气质英气倔强，力量型硬核少女声。]"
        }
    },
    {
        "id": "fc17568e-b0f7-4de0-9775-fe01848cbe50",
        "name": "绵糯少女",
        "language": "zh-CN",
        "gender": "female",
        "style": "温柔·甜美·自然",
        "tags": [
            "有声书",
            "少年",
            "少女",
            "13-17岁",
            "温柔",
            "甜美",
            "自然"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "fc17568e-b0f7-4de0-9775-fe01848cbe50",
            "age_stage": "teen",
            "age_min": 13,
            "age_max": 17,
            "accent": "standard-mandarin",
            "group": "少女",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "暖调绵柔、咬字黏软的少年女声，治愈乖巧，适合有声书中的软糯少女角色。",
            "vv_style": "少女",
            "vv_tags": [
                "少女",
                "13-17岁"
            ],
            "persona": "[#设定：女声，年龄13-17岁。强制物理级锁定暖调绵柔底层声基，温润下沉中频，彻底屏蔽冷薄声底、冷锐高频、干涩音色。声基底自带暖糯底色，柔和绵密，咬字黏软舒缓，气质治愈乖巧，暖底软糯少女原声。]"
        }
    },
    {
        "id": "1a78f3ae-08df-46ad-9919-04d3a08719b0",
        "name": "脆利少女",
        "language": "zh-CN",
        "gender": "female",
        "style": "活泼·轻快·自然",
        "tags": [
            "有声书",
            "少年",
            "少女",
            "14-18岁",
            "活泼",
            "轻快",
            "自然"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "1a78f3ae-08df-46ad-9919-04d3a08719b0",
            "age_stage": "teen",
            "age_min": 14,
            "age_max": 18,
            "accent": "standard-mandarin",
            "group": "少女",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "短促清脆、咬字紧凑的少年女声，机灵俏皮，适合有声书中的灵动少女角色。",
            "vv_style": "少女",
            "vv_tags": [
                "少女",
                "14-18岁"
            ],
            "persona": "[#设定：女声，年龄14-18岁。强制物理级锁定短音节脆裂发声基底，快节奏割裂短频，彻底屏蔽长腔拖音、慢缓声线、绵长共鸣。声基底短促清脆，音节利落利落，咬字紧凑灵动，气质机灵俏皮，短脆快频少女嗓。]"
        }
    },
    {
        "id": "215cbf58-95a0-43b2-bcce-c9c5a7700e65",
        "name": "古韵少女",
        "language": "zh-CN",
        "gender": "female",
        "style": "古风·温柔·自然",
        "tags": [
            "有声书",
            "少年",
            "少女",
            "15-19岁",
            "古风",
            "温柔",
            "自然"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "215cbf58-95a0-43b2-bcce-c9c5a7700e65",
            "age_stage": "teen",
            "age_min": 15,
            "age_max": 19,
            "accent": "standard-mandarin",
            "group": "少女",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "古典婉转、尾音延绵的少年女声，温婉含蓄，适合有声书中的古风少女角色。",
            "vv_style": "少女",
            "vv_tags": [
                "少女",
                "15-19岁"
            ],
            "persona": "[#设定：女声，年龄15-19岁。强制物理级锁定古风婉转折腔基底，轻韵婉转特殊声段，彻底屏蔽现代直白平嗓、生硬直音、短促节奏。声基底自带古典折韵，尾音婉转延绵，咬字含腔含蓄，气质古典温婉，古韵折腔少女声。]"
        }
    },
    {
        "id": "734f535d-7c7c-4009-ad2b-197064d5ce9a",
        "name": "机械少女",
        "language": "zh-CN",
        "gender": "female",
        "style": "高冷·专业·沉稳",
        "tags": [
            "有声书",
            "少年",
            "少女",
            "14-17岁",
            "高冷",
            "专业",
            "沉稳"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "734f535d-7c7c-4009-ad2b-197064d5ce9a",
            "age_stage": "teen",
            "age_min": 14,
            "age_max": 17,
            "accent": "standard-mandarin",
            "group": "少女",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "冷调平直、咬字精准的少年女声，理性疏离，适合有声书中的机械少女角色。",
            "vv_style": "少女",
            "vv_tags": [
                "少女",
                "14-17岁"
            ],
            "persona": "[#设定：女声，年龄14-17岁。强制物理级锁定金属冷调平直基底，无情绪无感单频段，彻底屏蔽人声柔糯、暖调共鸣、音色起伏。声基底冰冷平整，无多余泛音，咬字精准刻板，气质三无疏离，机械理性少女声线。]"
        }
    },
    {
        "id": "5044da81-c7f3-467c-8c10-cf832bb9c334",
        "name": "温柔少女",
        "language": "zh-CN",
        "gender": "female",
        "style": "温柔·感伤·自然",
        "tags": [
            "有声书",
            "少年",
            "少女",
            "15-18岁",
            "温柔",
            "感伤",
            "自然"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "5044da81-c7f3-467c-8c10-cf832bb9c334",
            "age_stage": "teen",
            "age_min": 15,
            "age_max": 18,
            "accent": "standard-mandarin",
            "group": "少女",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "湿软温润、咬字绵缓的少年女声，安静忧郁，适合有声书中的温柔少女角色。",
            "vv_style": "少女",
            "vv_tags": [
                "少女",
                "15-18岁"
            ],
            "persona": "[#设定：女声，年龄15-18岁。强制物理级锁定湿润柔厚声质基底，湿感柔和中低频，彻底屏蔽干薄燥声、干裂颗粒、尖锐头腔音。声基底湿软温润，音色暗沉柔和，咬字绵缓松弛，气质安静忧郁，湿柔沉润少女嗓。]"
        }
    },
    {
        "id": "b67949c7-1dba-44c5-9a88-003616d3e3cd",
        "name": "素淡少女",
        "language": "zh-CN",
        "gender": "female",
        "style": "高冷·沉稳·自然",
        "tags": [
            "有声书",
            "少年",
            "少女",
            "13-16岁",
            "高冷",
            "沉稳",
            "自然"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "b67949c7-1dba-44c5-9a88-003616d3e3cd",
            "age_stage": "teen",
            "age_min": 13,
            "age_max": 16,
            "accent": "standard-mandarin",
            "group": "少女",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "素净单薄、咬字轻敛的少年女声，清寂安静，适合有声书中的淡泊少女角色。",
            "vv_style": "少女",
            "vv_tags": [
                "少女",
                "13-16岁"
            ],
            "persona": "[#设定：女声，年龄13-16岁。强制物理级锁定无色极简清寂声底，低起伏素净中频，彻底屏蔽浓甜媚嗓、厚重共鸣、高亮外放。声基底素净单薄，音色极简清淡，咬字轻敛平缓，气质安静无欲，清寂佛系少女原声。]"
        }
    },
    {
        "id": "f3898f7c-9b2c-401c-9626-519692c59c8f",
        "name": "邻家哥哥",
        "language": "zh-CN",
        "gender": "male",
        "style": "温柔·自然·轻快",
        "tags": [
            "有声书",
            "青年",
            "男青年",
            "22-27岁",
            "温柔",
            "自然",
            "轻快"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "f3898f7c-9b2c-401c-9626-519692c59c8f",
            "age_stage": "young_adult",
            "age_min": 22,
            "age_max": 27,
            "accent": "standard-mandarin",
            "group": "男青年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "轻柔暖调、咬字舒缓的青年男声，治愈贴心，适合有声书中的邻家哥哥角色。",
            "vv_style": "男青年",
            "vv_tags": [
                "男青年",
                "22-27岁"
            ],
            "persona": "[#设定：男声，年龄22-27岁。强制物理级锁定轻柔暖调中音，弱气息轻共鸣，彻底屏蔽冷硬、沙哑、强势。声线软和细腻，像轻声耳语，咬字轻柔舒缓，气质治愈安抚，适合电台、哄睡、贴心陪伴型角色。]"
        }
    },
    {
        "id": "a57adacb-e243-4ef7-a4fa-6698516bca0c",
        "name": "内敛青年",
        "language": "zh-CN",
        "gender": "male",
        "style": "沉稳·磁性·自然",
        "tags": [
            "有声书",
            "青年",
            "男青年",
            "25-30岁",
            "沉稳",
            "磁性",
            "自然"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "a57adacb-e243-4ef7-a4fa-6698516bca0c",
            "age_stage": "young_adult",
            "age_min": 25,
            "age_max": 30,
            "accent": "standard-mandarin",
            "group": "男青年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "宽厚低沉、咬字稳重的青年男声，可靠克制，适合有声书中的成熟青年角色。",
            "vv_style": "男青年",
            "vv_tags": [
                "男青年",
                "25-30岁"
            ],
            "persona": "[#设定：男声，年龄25-30岁。强制物理级锁定宽厚中低音区，强胸腔共鸣，喉位稳定低沉，彻底屏蔽尖锐、单薄、稚嫩。声线沉而不哑，厚而不浊，自带包裹感与安全感，咬字沉稳有力，语速偏缓，语气克制稳重，适合成熟可靠、气场内敛型男性。]"
        }
    },
    {
        "id": "b726c63a-947a-477d-a0ce-76125560ab63",
        "name": "跳脱青年",
        "language": "zh-CN",
        "gender": "male",
        "style": "活泼·轻快·搞笑",
        "tags": [
            "有声书",
            "青年",
            "男青年",
            "19-22岁",
            "活泼",
            "轻快",
            "搞笑"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "b726c63a-947a-477d-a0ce-76125560ab63",
            "age_stage": "young_adult",
            "age_min": 19,
            "age_max": 22,
            "accent": "standard-mandarin",
            "group": "男青年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "轻快灵动、咬字活泼的青年男声，机灵好动，适合有声书中的跳脱青年角色。",
            "vv_style": "男青年",
            "vv_tags": [
                "男青年",
                "19-22岁"
            ],
            "persona": "[#设定：男声，年龄19-22岁。强制物理级锁定灵动活泼中高音，声线轻快跳跃，强气息灵动度，彻底屏蔽沉稳、低沉、木讷。咬字灵动，语速适中，气质活泼好动，适合机灵调皮、话多灵动的少年。]"
        }
    },
    {
        "id": "8434ddae-f9ad-4a29-b7b4-f5ad9a4d4ecf",
        "name": "慵懒青年",
        "language": "zh-CN",
        "gender": "male",
        "style": "慵懒·磁性·自然",
        "tags": [
            "有声书",
            "青年",
            "男青年",
            "24-29岁",
            "慵懒",
            "磁性",
            "自然"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "8434ddae-f9ad-4a29-b7b4-f5ad9a4d4ecf",
            "age_stage": "young_adult",
            "age_min": 24,
            "age_max": 29,
            "accent": "standard-mandarin",
            "group": "男青年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "微哑松弛、咬字散漫的青年男声，慵懒有故事感，适合有声书中的随性青年角色。",
            "vv_style": "男青年",
            "vv_tags": [
                "男青年",
                "24-29岁"
            ],
            "persona": "[#设定：男声，年龄24-29岁。强制物理级锁定微哑松弛中低音，轻微声带摩擦感，弱胸腔共鸣，彻底屏蔽清亮、干净、甜腻。声线带轻微磨砂颗粒，慵懒松弛，有轻微风尘感与故事感，咬字散漫，语速慵懒，适合随性、颓废、有阅历的角色。]"
        }
    },
    {
        "id": "0e083fb0-9e74-411a-ab15-9b86c045bda1",
        "name": "霸道总裁",
        "language": "zh-CN",
        "gender": "male",
        "style": "霸总·沉稳·磁性",
        "tags": [
            "有声书",
            "青年",
            "男青年",
            "26-32岁",
            "霸总",
            "沉稳",
            "磁性"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "0e083fb0-9e74-411a-ab15-9b86c045bda1",
            "age_stage": "young_adult",
            "age_min": 26,
            "age_max": 32,
            "accent": "standard-mandarin",
            "group": "男青年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "沉硬紧实、咬字果决的青年男声，强势笃定，适合有声书中的霸道总裁角色。",
            "vv_style": "男青年",
            "vv_tags": [
                "男青年",
                "26-32岁"
            ],
            "persona": "[#设定：男声，年龄26-32岁。强制物理级锁定沉硬中低音，强胸腔支撑，声线紧实有力，彻底屏蔽慵懒、软弱、轻浮。咬字短促果决，语速偏慢但气场强，语气强势笃定，适合杀伐果断、掌控欲强的强势角色。]"
        }
    },
    {
        "id": "be995322-cd86-4ccf-81d5-0562193ebf5b",
        "name": "憨厚暖男",
        "language": "zh-CN",
        "gender": "male",
        "style": "温柔·自然·沉稳",
        "tags": [
            "有声书",
            "青年",
            "男青年",
            "22-27岁",
            "温柔",
            "自然",
            "沉稳"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "be995322-cd86-4ccf-81d5-0562193ebf5b",
            "age_stage": "young_adult",
            "age_min": 22,
            "age_max": 27,
            "accent": "standard-mandarin",
            "group": "男青年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "宽厚平实、咬字诚恳的青年男声，老实温和，适合有声书中的憨厚暖男角色。",
            "vv_style": "男青年",
            "vv_tags": [
                "男青年",
                "22-27岁"
            ],
            "persona": "[#设定：男声，年龄22-27岁。强制物理级锁定宽厚平实中音，无修饰、无棱角，彻底屏蔽凌厉、腹黑、油腻。声线敦厚踏实，咬字扎实诚恳，语速平稳温和，气质老实可靠、不善言辞但内心温柔。]"
        }
    },
    {
        "id": "055556fa-a1a7-451d-95ff-debf4ee842d8",
        "name": "轻挑痞帅",
        "language": "zh-CN",
        "gender": "male",
        "style": "慵懒·性感·自然",
        "tags": [
            "有声书",
            "青年",
            "男青年",
            "23-28岁",
            "慵懒",
            "性感",
            "自然"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "055556fa-a1a7-451d-95ff-debf4ee842d8",
            "age_stage": "young_adult",
            "age_min": 23,
            "age_max": 28,
            "accent": "standard-mandarin",
            "group": "男青年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "松弛略哑、尾音微扬的青年男声，玩世不恭，适合有声书中的痞帅角色。",
            "vv_style": "男青年",
            "vv_tags": [
                "男青年",
                "23-28岁"
            ],
            "persona": "[#设定：男声，年龄23-28岁。强制物理级锁定松弛略哑中音，声线带轻挑弧度，彻底屏蔽正经、刻板、憨厚。咬字随性轻慢，尾音微扬带玩味，语气玩世不恭，适合嘴贫、会撩、略带坏气的痞帅角色。]"
        }
    },
    {
        "id": "064935d9-e4bf-44db-b285-30a912fce031",
        "name": "爽朗阳光",
        "language": "zh-CN",
        "gender": "male",
        "style": "活泼·轻快·热血",
        "tags": [
            "有声书",
            "青年",
            "男青年",
            "21-25岁",
            "活泼",
            "轻快",
            "热血"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "064935d9-e4bf-44db-b285-30a912fce031",
            "age_stage": "young_adult",
            "age_min": 21,
            "age_max": 25,
            "accent": "standard-mandarin",
            "group": "男青年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "开阔明亮、咬字有力的青年男声，热情外向，适合有声书中的阳光青年角色。",
            "vv_style": "男青年",
            "vv_tags": [
                "男青年",
                "21-25岁"
            ],
            "persona": "[#设定：男声，年龄21-25岁。强制物理级锁定开阔明亮中高音，强气息通透感，彻底屏蔽阴郁、低沉、内敛。声线开阔洪亮，咬字清晰有力，语速轻快热情，适合运动少年、外向开朗、人缘极好的社交型角色。]"
        }
    },
    {
        "id": "c5679fd7-8a5a-411b-ae45-1a915fb41025",
        "name": "世家公子",
        "language": "zh-CN",
        "gender": "male",
        "style": "古风·温柔·沉稳",
        "tags": [
            "有声书",
            "青年",
            "男青年",
            "24-29岁",
            "古风",
            "温柔",
            "沉稳"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "c5679fd7-8a5a-411b-ae45-1a915fb41025",
            "age_stage": "young_adult",
            "age_min": 24,
            "age_max": 29,
            "accent": "standard-mandarin",
            "group": "男青年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "古韵绵长、咬字婉转的青年男声，温文尔雅，适合有声书中的世家公子角色。",
            "vv_style": "男青年",
            "vv_tags": [
                "男青年",
                "24-29岁"
            ],
            "persona": "[#设定：男声，年龄24-29岁。强制物理级锁定绵长柔和中低音，行腔婉转舒缓，彻底屏蔽现代硬朗、短促直白、粗犷。声线古韵绵长，咬字圆润婉转，气质温文尔雅，适合古风公子、世家子弟。]"
        }
    },
    {
        "id": "fe5fcb88-c6e3-4bc2-91a0-2b98b2d437a4",
        "name": "心机撩人",
        "language": "zh-CN",
        "gender": "male",
        "style": "性感·腹黑·慵懒",
        "tags": [
            "有声书",
            "青年",
            "男青年",
            "24-29岁",
            "性感",
            "腹黑",
            "慵懒"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "fe5fcb88-c6e3-4bc2-91a0-2b98b2d437a4",
            "age_stage": "young_adult",
            "age_min": 24,
            "age_max": 29,
            "accent": "standard-mandarin",
            "group": "男青年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "柔滑偏低、尾音微勾的青年男声，邪魅撩人，适合有声书中的腹黑妖孽角色。",
            "vv_style": "男青年",
            "vv_tags": [
                "男青年",
                "24-29岁"
            ],
            "persona": "[#设定：男声，年龄24-29岁。强制物理级锁定柔滑偏低中音，声线带轻微气音缠绕感，彻底屏蔽正直、憨厚、生硬。咬字轻缓撩人，尾音微勾，气质邪魅魅惑，适合美强妖孽、心机撩人型角色。]"
        }
    },
    {
        "id": "7e2db873-eb4b-4602-9f07-b4abb2185a65",
        "name": "温润男友",
        "language": "zh-CN",
        "gender": "male",
        "style": "温柔·性感·自然",
        "tags": [
            "有声书",
            "青年",
            "男青年",
            "23-28岁",
            "温柔",
            "性感",
            "自然"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "7e2db873-eb4b-4602-9f07-b4abb2185a65",
            "age_stage": "young_adult",
            "age_min": 23,
            "age_max": 28,
            "accent": "standard-mandarin",
            "group": "男青年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "贴耳低柔、咬字舒缓的青年男声，亲密温润，适合有声书中的温柔男友角色。",
            "vv_style": "男青年",
            "vv_tags": [
                "男青年",
                "23-28岁"
            ],
            "persona": "[#设定：男声，年龄23-28岁。强制物理级锁定贴近耳际低中音，轻气声包裹，弱共鸣柔化处理，彻底屏蔽洪亮、尖锐、外放。声线亲密温柔，如耳边低语，语速轻柔缓慢，适合亲密宠溺、温柔告白、贴身陪伴型男友。]"
        }
    },
    {
        "id": "095baf2c-b3fb-431d-8f7f-7d35e9897391",
        "name": "傲娇青年",
        "language": "zh-CN",
        "gender": "male",
        "style": "高冷·腹黑·轻快",
        "tags": [
            "有声书",
            "青年",
            "男青年",
            "21-26岁",
            "高冷",
            "腹黑",
            "轻快"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "095baf2c-b3fb-431d-8f7f-7d35e9897391",
            "age_stage": "young_adult",
            "age_min": 21,
            "age_max": 26,
            "accent": "standard-mandarin",
            "group": "男青年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "清亮锐利、咬字利落的青年男声，嘴硬毒舌，适合有声书中的傲娇青年角色。",
            "vv_style": "男青年",
            "vv_tags": [
                "男青年",
                "21-26岁"
            ],
            "persona": "[#设定：男声，年龄21-26岁。强制物理级锁定清亮锐利中音，声线偏薄带刺，咬字清晰利落，彻底屏蔽憨厚、温和、迟钝。语气带轻微嘲讽与傲娇感，语速偏快节奏强，适合嘴硬心软、智商高、傲娇毒舌型青年。]"
        }
    },
    {
        "id": "72ff81fe-9709-4c9c-8ff1-a68bbdb97e3e",
        "name": "校园男神",
        "language": "zh-CN",
        "gender": "male",
        "style": "温柔·自然·轻快",
        "tags": [
            "有声书",
            "青年",
            "男青年",
            "20-24岁",
            "温柔",
            "自然",
            "轻快"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "72ff81fe-9709-4c9c-8ff1-a68bbdb97e3e",
            "age_stage": "young_adult",
            "age_min": 20,
            "age_max": 24,
            "accent": "standard-mandarin",
            "group": "男青年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "清爽通透、咬字轻快的青年男声，阳光干净，适合有声书中的校园男神角色。",
            "vv_style": "男青年",
            "vv_tags": [
                "男青年",
                "20-24岁"
            ],
            "persona": "[#设定：男声，年龄20-24岁。强制物理级锁定清爽通透中音，无杂质无颗粒感，轻气息明亮感，彻底屏蔽沙哑、油腻、低沉。声线干净纯粹，咬字清晰轻快，气质阳光干净，适合校园男神、温柔校草形象。]"
        }
    },
    {
        "id": "253b8239-5141-48b0-ae15-c31f7cc6d965",
        "name": "霸气枭雄",
        "language": "zh-CN",
        "gender": "male",
        "style": "霸总·热血·沉稳",
        "tags": [
            "有声书",
            "青年",
            "男青年",
            "26-32岁",
            "霸总",
            "热血",
            "沉稳"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "253b8239-5141-48b0-ae15-c31f7cc6d965",
            "age_stage": "young_adult",
            "age_min": 26,
            "age_max": 32,
            "accent": "standard-mandarin",
            "group": "男青年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "沉厉霸气、咬字铿锵的青年男声，狂傲强势，适合有声书中的枭雄霸主角色。",
            "vv_style": "男青年",
            "vv_tags": [
                "男青年",
                "26-32岁"
            ],
            "persona": "[#设定：男声，年龄26-32岁。强制物理级锁定沉厉霸气中低音，强气息压迫感，彻底屏蔽温和、怯懦、内敛。声线狂傲凌厉，咬字铿锵有力，语气睥睨自负，适合枭雄、霸主、野心勃勃的掌权者。]"
        }
    },
    {
        "id": "a4760d3e-6a91-4e8d-978b-08468c88c6cd",
        "name": "社团大哥",
        "language": "zh-CN",
        "gender": "male",
        "style": "热血·活泼·自然",
        "tags": [
            "有声书",
            "青年",
            "男青年",
            "25-30岁",
            "热血",
            "活泼",
            "自然"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "a4760d3e-6a91-4e8d-978b-08468c88c6cd",
            "age_stage": "young_adult",
            "age_min": 25,
            "age_max": 30,
            "accent": "standard-mandarin",
            "group": "男青年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "宽厚开阔、咬字豪爽的青年男声，仗义可靠，适合有声书中的社团大哥角色。",
            "vv_style": "男青年",
            "vv_tags": [
                "男青年",
                "25-30岁"
            ],
            "persona": "[#设定：男声，年龄25-30岁。强制物理级锁定宽厚豪爽中音，声线开阔仗义，强气息支撑，彻底屏蔽小气、阴柔、拘谨。咬字扎实豪爽，语速明快热情，气质仗义可靠，适合团队大哥、仗义兄弟、领头人角色。]"
        }
    },
    {
        "id": "076a012b-2f97-4c4b-a03e-d4d6eda35cbd",
        "name": "圣洁牧师",
        "language": "zh-CN",
        "gender": "male",
        "style": "温柔·专业·沉稳",
        "tags": [
            "有声书",
            "青年",
            "男青年",
            "26-31岁",
            "温柔",
            "专业",
            "沉稳"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "076a012b-2f97-4c4b-a03e-d4d6eda35cbd",
            "age_stage": "young_adult",
            "age_min": 26,
            "age_max": 31,
            "accent": "standard-mandarin",
            "group": "男青年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "宽厚温和、咬字庄重的青年男声，慈悲包容，适合有声书中的牧师救赎者角色。",
            "vv_style": "男青年",
            "vv_tags": [
                "男青年",
                "26-31岁"
            ],
            "persona": "[#设定：男声，年龄26-31岁。强制物理级锁定宽厚慈悲中低音，声线温和包容，强安抚感共鸣，彻底屏蔽尖锐、强势、冷漠。咬字从容慈悲，语速平缓庄重，气质温柔圣洁，适合神父、牧师、慈悲救赎者角色。]"
        }
    },
    {
        "id": "4eadf901-7efb-46c4-9ef6-c7c3d271b9d7",
        "name": "清冷公子",
        "language": "zh-CN",
        "gender": "male",
        "style": "高冷·古风·自然",
        "tags": [
            "有声书",
            "青年",
            "男青年",
            "21-25岁",
            "高冷",
            "古风",
            "自然"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "4eadf901-7efb-46c4-9ef6-c7c3d271b9d7",
            "age_stage": "young_adult",
            "age_min": 21,
            "age_max": 25,
            "accent": "standard-mandarin",
            "group": "男青年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "清冽干净、咬字爽利的青年男声，贵气疏离，适合有声书中的清冷公子角色。",
            "vv_style": "男青年",
            "vv_tags": [
                "男青年",
                "21-25岁"
            ],
            "persona": "[#设定：男声，年龄21-25岁。强制物理级锁定清冽干净中音区，轻头腔共鸣，声线干净无杂质，彻底屏蔽沙哑、厚重、油腻。咬字利落清爽，语速适中偏快，气质干净贵气，适合清冷俊秀、家教良好的世家少年。]"
        }
    },
    {
        "id": "4389f6dc-52b1-4c4f-b3f5-3548882f43e7",
        "name": "阴柔公子",
        "language": "zh-CN",
        "gender": "male",
        "style": "腹黑·高冷·性感",
        "tags": [
            "有声书",
            "青年",
            "男青年",
            "24-29岁",
            "腹黑",
            "高冷",
            "性感"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "4389f6dc-52b1-4c4f-b3f5-3548882f43e7",
            "age_stage": "young_adult",
            "age_min": 24,
            "age_max": 29,
            "accent": "standard-mandarin",
            "group": "男青年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "诡魅飘忽、咬字阴柔的青年男声，邪魅莫测，适合有声书中的神秘反派角色。",
            "vv_style": "男青年",
            "vv_tags": [
                "男青年",
                "24-29岁"
            ],
            "persona": "[#设定：男声，年龄24-29岁。强制物理级锁定诡魅飘忽中音，带轻微气声诡异感，彻底屏蔽正气、温和、直白。声线妖异莫测，咬字轻缓阴柔，气质邪魅诡谲，适合邪修、妖物、神秘反派。]"
        }
    },
    {
        "id": "1e61010a-4a59-471c-ae49-dd14ab618204",
        "name": "热枕青年",
        "language": "zh-CN",
        "gender": "male",
        "style": "热血·沉稳·专业",
        "tags": [
            "有声书",
            "青年",
            "男青年",
            "22-28岁",
            "热血",
            "沉稳",
            "专业"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "1e61010a-4a59-471c-ae49-dd14ab618204",
            "age_stage": "young_adult",
            "age_min": 22,
            "age_max": 28,
            "accent": "standard-mandarin",
            "group": "男青年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "铿锵硬朗、咬字有力的青年男声，正气热忱，适合有声书中的铁血青年角色。",
            "vv_style": "男青年",
            "vv_tags": [
                "男青年",
                "22-28岁"
            ],
            "persona": "[#设定：男声，年龄22-28岁。强制物理级锁定铿锵有力中低音绝对频段，底层强制开启刚毅正气胸腔共鸣与硬朗有劲喉结发音，彻底屏蔽柔弱怯懦、慵懒散漫与阴柔做作感，全程硬朗铁血。赋予声音在男青年声基底上刚毅正直、正气凛然的独特质感，声线铿锵有劲，带铁血颗粒感，沉稳果敢一身正气，绝对禁止怯懦、禁止散漫、禁止做作伪音。咬字铿锵有力，语速沉稳顿挫，尾音硬朗收束，语气正气果敢，能在绝对纯正男青年音域内完成坚定陈述、沉稳轻笑、正义提问、刚毅感慨，绝不柔弱怯懦、绝不慵懒散漫。硬朗铁血感、军人男青感、看似刚毅果决实则心怀热忱的男青年声线，说话带贴耳劲爽呼吸感，用焊死中低音的极致力体感，打造铁血军人故事感男声。]"
        }
    },
    {
        "id": "36a4929f-e1a7-4ea7-8d29-b4bad2631844",
        "name": "清爽男大",
        "language": "zh-CN",
        "gender": "male",
        "style": "少年感·自然",
        "tags": [
            "有声书",
            "青年",
            "男青年",
            "19-22岁",
            "少年感",
            "自然"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "36a4929f-e1a7-4ea7-8d29-b4bad2631844",
            "age_stage": "young_adult",
            "age_min": 19,
            "age_max": 22,
            "accent": "standard-mandarin",
            "group": "男青年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "",
            "vv_style": "男青年",
            "vv_tags": [
                "男青年",
                "19-22岁"
            ],
            "persona": "[#设定：男声，年龄19-22岁。强制物理级锁定清亮通透少年中音绝对频段，底层强制开启干净松弛胸腔共鸣与清朗利落喉结发音，彻底屏蔽低沉油腻、沙哑粗重与沉闷世故感，全程清爽干净。赋予声音在中音区少年基底上清亮阳光、干净纯粹的独特质感，声线清亮舒展，带校园朝气颗粒感，开朗温和朝气蓬勃，绝对禁止油腻、禁止沙哑、禁止世故伪音。咬字清晰干净，语速轻快自然，尾音清爽利落，语气阳光随和，能在绝对纯正少年音域内完成轻松讲述、爽朗轻笑、温和劝慰、朝气感慨，绝不油腻沉闷、绝不世故老成。校园男大学生感、清爽阳光、看似随性开朗实则真诚纯粹的少年男声，说话带贴耳干净呼吸感，用焊死少年中音的极致清爽感，打造青春校园故事感少年声线。]"
        }
    },
    {
        "id": "d6d50d0b-d58a-4d9f-b5ce-e3741007d19b",
        "name": "戏腔柔韵",
        "language": "zh-CN",
        "gender": "female",
        "style": "古风·温柔·自然",
        "tags": [
            "有声书",
            "青年",
            "女青年",
            "22-26岁",
            "古风",
            "温柔",
            "自然"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "d6d50d0b-d58a-4d9f-b5ce-e3741007d19b",
            "age_stage": "young_adult",
            "age_min": 22,
            "age_max": 26,
            "accent": "standard-mandarin",
            "group": "女青年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "婉转含韵、咬字绵长的青年女声，古典雅致，适合有声书中的国风戏感角色。",
            "vv_style": "女青年",
            "vv_tags": [
                "女青年",
                "22-26岁"
            ],
            "persona": "[#设定：女声，年龄22-26岁。强制物理级锁定婉转韵感柔音，拖腔浅共鸣，彻底屏蔽直白、生硬、粗哑。声线婉转含韵，咬字柔婉绵长，气质古典雅致，适合古风戏感、国风少女角色。]"
        }
    },
    {
        "id": "5bde2a41-2818-4900-9da4-f67b4363141d",
        "name": "清冽薄荷",
        "language": "zh-CN",
        "gender": "female",
        "style": "高冷·自然·轻快",
        "tags": [
            "有声书",
            "青年",
            "女青年",
            "20-25岁",
            "高冷",
            "自然",
            "轻快"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "5bde2a41-2818-4900-9da4-f67b4363141d",
            "age_stage": "young_adult",
            "age_min": 20,
            "age_max": 25,
            "accent": "standard-mandarin",
            "group": "女青年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "薄凉通透、咬字利落的青年女声，清新冷淡，适合有声书中的薄荷系少女角色。",
            "vv_style": "女青年",
            "vv_tags": [
                "女青年",
                "20-25岁"
            ],
            "persona": "[#设定：女声，年龄20-25岁。强制物理级锁定薄凉通透中音，干净浅气息，彻底屏蔽甜腻、厚重、暖浊。声线清爽微凉，咬字干净利落，气质冷淡清新，适合薄荷系、清冷文艺少女角色。]"
        }
    },
    {
        "id": "8480f9bd-c33c-4758-9377-40c4f2e10214",
        "name": "蜜语甜御",
        "language": "zh-CN",
        "gender": "female",
        "style": "甜美·温柔·性感",
        "tags": [
            "有声书",
            "青年",
            "女青年",
            "24-29岁",
            "甜美",
            "温柔",
            "性感"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "8480f9bd-c33c-4758-9377-40c4f2e10214",
            "age_stage": "young_adult",
            "age_min": 24,
            "age_max": 29,
            "accent": "standard-mandarin",
            "group": "女青年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "蜜感柔润、咬字婉转的青年女声，轻熟妩媚，适合有声书中的甜御姐姐角色。",
            "vv_style": "女青年",
            "vv_tags": [
                "女青年",
                "24-29岁"
            ],
            "persona": "[#设定：女声，年龄24-29岁。强制物理级锁定蜜感柔润中低音，绵密柔共鸣，彻底屏蔽单薄、尖锐、冰冷。声线柔媚清甜，咬字婉转柔和，气质轻熟妩媚，适合甜御系、温柔姐姐角色。]"
        }
    },
    {
        "id": "3b54d5f8-cfc3-4859-92df-f529529b46c9",
        "name": "英气少女",
        "language": "zh-CN",
        "gender": "female",
        "style": "热血·自然·轻快",
        "tags": [
            "有声书",
            "青年",
            "女青年",
            "21-26岁",
            "热血",
            "自然",
            "轻快"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "3b54d5f8-cfc3-4859-92df-f529529b46c9",
            "age_stage": "young_adult",
            "age_min": 21,
            "age_max": 26,
            "accent": "standard-mandarin",
            "group": "女青年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "平直清爽、咬字短促的青年女声，清朗英气，适合有声书中的中性少女角色。",
            "vv_style": "女青年",
            "vv_tags": [
                "女青年",
                "21-26岁"
            ],
            "persona": "[#设定：女声，年龄21-26岁。强制物理级锁定平直清爽中性音，利落实声，彻底屏蔽柔媚、娇柔、细弱。声线清朗英气，咬字短促利落，气质少年感十足，适合短发英气、中性少女角色。]"
        }
    },
    {
        "id": "41a73dd7-6f9c-4a0e-bc6d-7f755c50abb1",
        "name": "泼辣媚音",
        "language": "zh-CN",
        "gender": "female",
        "style": "御姐感·活泼·性感",
        "tags": [
            "有声书",
            "青年",
            "女青年",
            "23-27岁",
            "御姐感",
            "活泼",
            "性感"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "41a73dd7-6f9c-4a0e-bc6d-7f755c50abb1",
            "age_stage": "young_adult",
            "age_min": 23,
            "age_max": 27,
            "accent": "standard-mandarin",
            "group": "女青年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "高亮明艳、咬字灵动的青年女声，张扬泼辣，适合有声书中的风情女主角色。",
            "vv_style": "女青年",
            "vv_tags": [
                "女青年",
                "23-27岁"
            ],
            "persona": "[#设定：女声，年龄23-27岁。强制物理级锁定高亮媚态中音，外放柔气息，彻底屏蔽沉闷、内敛、寡淡。声线明艳张扬，咬字灵动带媚，气质明艳泼辣，适合明艳女主、风情少女角色。]"
        }
    },
    {
        "id": "5512a1af-f33a-4a2a-98c7-72461b49e291",
        "name": "书卷文雅",
        "language": "zh-CN",
        "gender": "female",
        "style": "温柔·专业·自然",
        "tags": [
            "有声书",
            "青年",
            "女青年",
            "22-27岁",
            "温柔",
            "专业",
            "自然"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "5512a1af-f33a-4a2a-98c7-72461b49e291",
            "age_stage": "young_adult",
            "age_min": 22,
            "age_max": 27,
            "accent": "standard-mandarin",
            "group": "女青年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "平稳温润、咬字规整的青年女声，书香恬静，适合有声书中的文雅学姐角色。",
            "vv_style": "女青年",
            "vv_tags": [
                "女青年",
                "22-27岁"
            ],
            "persona": "[#设定：女声，年龄22-27岁。强制物理级锁定平稳文雅中音，克制缓气息，彻底屏蔽跳脱、聒噪、甜俗。声线沉静文雅，咬字规整温润，气质书香恬静，适合文艺学姐、书香少女角色。]"
        }
    },
    {
        "id": "ceef1b9b-7528-4d1f-882d-18ea1cba1ec6",
        "name": "雾感朦胧",
        "language": "zh-CN",
        "gender": "female",
        "style": "温柔·慵懒·自然",
        "tags": [
            "有声书",
            "青年",
            "女青年",
            "21-25岁",
            "温柔",
            "慵懒",
            "自然"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "ceef1b9b-7528-4d1f-882d-18ea1cba1ec6",
            "age_stage": "young_adult",
            "age_min": 21,
            "age_max": 25,
            "accent": "standard-mandarin",
            "group": "女青年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "朦胧柔和、咬字轻浅的青年女声，氛围迷离，适合有声书中的雾感少女角色。",
            "vv_style": "女青年",
            "vv_tags": [
                "女青年",
                "21-25岁"
            ],
            "persona": "[#设定：女声，年龄21-25岁。强制物理级锁定雾感朦胧柔音，朦胧混响质感，彻底屏蔽通透、锐利、清亮。声线朦胧柔和，咬字轻浅模糊，气质氛围感拉满，适合雾感氛围感、朦胧少女角色。]"
        }
    },
    {
        "id": "dfd3abae-1182-4c82-815f-609d1bc900fd",
        "name": "元气甜辣",
        "language": "zh-CN",
        "gender": "female",
        "style": "活泼·轻快·甜美",
        "tags": [
            "有声书",
            "青年",
            "女青年",
            "20-24岁",
            "活泼",
            "轻快",
            "甜美"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "dfd3abae-1182-4c82-815f-609d1bc900fd",
            "age_stage": "young_adult",
            "age_min": 20,
            "age_max": 24,
            "accent": "standard-mandarin",
            "group": "女青年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "亮脆有劲、咬字利落的青年女声，甜辣鲜活，适合有声书中的潮流少女角色。",
            "vv_style": "女青年",
            "vv_tags": [
                "女青年",
                "20-24岁"
            ],
            "persona": "[#设定：女声，年龄20-24岁。强制物理级锁定亮脆劲爽高音，紧实活力气息，彻底屏蔽慵懒、低沉、软腻。声线脆亮有劲，咬字灵动利落，气质甜辣鲜活，适合甜辣风、潮流少女角色。]"
        }
    },
    {
        "id": "0acc3ab2-0dfa-410f-84e4-8d33ce748964",
        "name": "疏离冷艳",
        "language": "zh-CN",
        "gender": "female",
        "style": "高冷·御姐感·沉稳",
        "tags": [
            "有声书",
            "青年",
            "女青年",
            "24-28岁",
            "高冷",
            "御姐感",
            "沉稳"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "0acc3ab2-0dfa-410f-84e4-8d33ce748964",
            "age_stage": "young_adult",
            "age_min": 24,
            "age_max": 28,
            "accent": "standard-mandarin",
            "group": "女青年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "冷艳低缓、咬字淡漠的青年女声，高冷疏离，适合有声书中的冷艳女主角色。",
            "vv_style": "女青年",
            "vv_tags": [
                "女青年",
                "24-28岁"
            ],
            "persona": "[#设定：女声，年龄24-28岁。强制物理级锁定冷艳低缓中音，克制冷气息，彻底屏蔽热情、软糯、亲和。声线冷感厚重，咬字淡漠疏离，气质高冷美艳，适合高冷女神、冷艳女主角色。]"
        }
    },
    {
        "id": "a5e7d810-dc48-4c51-805e-0251758aecfd",
        "name": "温软邻家",
        "language": "zh-CN",
        "gender": "female",
        "style": "温柔·自然·甜美",
        "tags": [
            "有声书",
            "青年",
            "女青年",
            "20-25岁",
            "温柔",
            "自然",
            "甜美"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "a5e7d810-dc48-4c51-805e-0251758aecfd",
            "age_stage": "young_adult",
            "age_min": 20,
            "age_max": 25,
            "accent": "standard-mandarin",
            "group": "女青年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "朴实温厚、咬字自然的青年女声，亲切随和，适合有声书中的邻家女孩角色。",
            "vv_style": "女青年",
            "vv_tags": [
                "女青年",
                "20-25岁"
            ],
            "persona": "[#设定：女声，年龄20-25岁。强制物理级锁定朴实温厚中音，自然舒缓气息，彻底屏蔽精致、高冷、刻意。声线朴实温柔，咬字自然放松，气质亲切随和，适合邻家女孩、日常温柔角色。]"
        }
    },
    {
        "id": "f778a2b5-7838-4465-b460-a4c272267baf",
        "name": "碎音脆弱",
        "language": "zh-CN",
        "gender": "female",
        "style": "感伤·温柔·自然",
        "tags": [
            "有声书",
            "青年",
            "女青年",
            "21-26岁",
            "感伤",
            "温柔",
            "自然"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "f778a2b5-7838-4465-b460-a4c272267baf",
            "age_stage": "young_adult",
            "age_min": 21,
            "age_max": 26,
            "accent": "standard-mandarin",
            "group": "女青年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "细碎轻柔、咬字断续的青年女声，敏感脆弱，适合有声书中的易碎少女角色。",
            "vv_style": "女青年",
            "vv_tags": [
                "女青年",
                "21-26岁"
            ],
            "persona": "[#设定：女声，年龄21-26岁。强制物理级锁定细碎弱质轻音，断续柔气息，彻底屏蔽厚重、强硬、洪亮。声线细碎轻柔，咬字微弱断续，气质敏感脆弱，适合易碎感、内向少女角色。]"
        }
    },
    {
        "id": "d649dac3-e882-4649-9edb-37a6e3b69d77",
        "name": "阔朗大方",
        "language": "zh-CN",
        "gender": "female",
        "style": "活泼·自然·轻快",
        "tags": [
            "有声书",
            "青年",
            "女青年",
            "22-28岁",
            "活泼",
            "自然",
            "轻快"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "d649dac3-e882-4649-9edb-37a6e3b69d77",
            "age_stage": "young_adult",
            "age_min": 22,
            "age_max": 28,
            "accent": "standard-mandarin",
            "group": "女青年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "开阔饱满、咬字自然的青年女声，爽朗大气，适合有声书中的随性女孩角色。",
            "vv_style": "女青年",
            "vv_tags": [
                "女青年",
                "22-28岁"
            ],
            "persona": "[#设定：女声，年龄22-28岁。强制物理级锁定开阔饱满中音，充足舒展气息，彻底屏蔽狭隘、细弱、拘谨。声线洪亮舒展，咬字大方自然，气质爽朗大气，适合大方开朗、随性女孩角色。]"
        }
    },
    {
        "id": "9bd7b624-5282-4a57-8c2e-d63e2d01d085",
        "name": "柔媚古风",
        "language": "zh-CN",
        "gender": "female",
        "style": "古风·性感·温柔",
        "tags": [
            "有声书",
            "青年",
            "女青年",
            "23-27岁",
            "古风",
            "性感",
            "温柔"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "9bd7b624-5282-4a57-8c2e-d63e2d01d085",
            "age_stage": "young_adult",
            "age_min": 23,
            "age_max": 27,
            "accent": "standard-mandarin",
            "group": "女青年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "柔媚婉转、咬字含情的青年女声，温婉妩媚，适合有声书中的古风美人角色。",
            "vv_style": "女青年",
            "vv_tags": [
                "女青年",
                "23-27岁"
            ],
            "persona": "[#设定：女声，年龄23-27岁。强制物理级锁定柔媚婉转中低音，绵长柔气息，彻底屏蔽刚硬、直白、粗犷。声线妩媚婉转，咬字柔缓含情，气质温婉妩媚，适合古风美人、倾城闺秀角色。]"
        }
    },
    {
        "id": "9952bc10-163c-47af-a0ec-d9e698542e52",
        "name": "纯净小白",
        "language": "zh-CN",
        "gender": "female",
        "style": "甜美·自然·温柔",
        "tags": [
            "有声书",
            "青年",
            "女青年",
            "19-22岁",
            "甜美",
            "自然",
            "温柔"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "9952bc10-163c-47af-a0ec-d9e698542e52",
            "age_stage": "young_adult",
            "age_min": 19,
            "age_max": 22,
            "accent": "standard-mandarin",
            "group": "女青年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "纯净无瑕、咬字清甜的青年女声，单纯干净，适合有声书中的小白系少女角色。",
            "vv_style": "女青年",
            "vv_tags": [
                "女青年",
                "19-22岁"
            ],
            "persona": "[#设定：女声，年龄19-22岁。强制物理级锁定纯白无杂高音，干净浅气息，彻底屏蔽世故、沙哑、厚重。声线纯净无瑕，咬字清甜稚嫩，气质干净单纯，适合小白系、纯白少女角色。]"
        }
    },
    {
        "id": "f98df806-cf37-4150-83e0-d469f314d7fd",
        "name": "低沉冷调",
        "language": "zh-CN",
        "gender": "female",
        "style": "高冷·沉稳·磁性",
        "tags": [
            "有声书",
            "青年",
            "女青年",
            "24-29岁",
            "高冷",
            "沉稳",
            "磁性"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "f98df806-cf37-4150-83e0-d469f314d7fd",
            "age_stage": "young_adult",
            "age_min": 24,
            "age_max": 29,
            "accent": "standard-mandarin",
            "group": "女青年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "纯净低沉、咬字克制的青年女声，冷静内敛，适合有声书中的冷调御姐角色。",
            "vv_style": "女青年",
            "vv_tags": [
                "女青年",
                "24-29岁"
            ],
            "persona": "[#设定：女声，年龄24-29岁。强制物理级锁定纯净冷调低音，沉实薄气息，彻底屏蔽尖细、高甜、轻浮。声线低沉冷静，咬字沉稳克制，气质冷静内敛，适合冷调御姐、沉稳少女角色。]"
        }
    },
    {
        "id": "cb3940cf-1848-4e1e-9022-8b3baf1c9792",
        "name": "灵动雀跃",
        "language": "zh-CN",
        "gender": "female",
        "style": "活泼·轻快·甜美",
        "tags": [
            "有声书",
            "青年",
            "女青年",
            "19-23岁",
            "活泼",
            "轻快",
            "甜美"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "cb3940cf-1848-4e1e-9022-8b3baf1c9792",
            "age_stage": "young_adult",
            "age_min": 19,
            "age_max": 23,
            "accent": "standard-mandarin",
            "group": "女青年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "雀跃灵动、咬字轻快的青年女声，活泼俏皮，适合有声书中的灵动少女角色。",
            "vv_style": "女青年",
            "vv_tags": [
                "女青年",
                "19-23岁"
            ],
            "persona": "[#设定：女声，年龄19-23岁。强制物理级锁定跳跃灵动高音，轻快跳脱气息，彻底屏蔽沉闷、死板、缓慢。声线雀跃灵动，咬字短促轻快，气质活泼俏皮，适合灵动少女、活泼少女角色。]"
        }
    },
    {
        "id": "71e872c2-c7de-4c72-a8d9-6e32fbc09f38",
        "name": "复古温柔",
        "language": "zh-CN",
        "gender": "female",
        "style": "温柔·自然·慵懒",
        "tags": [
            "有声书",
            "青年",
            "女青年",
            "22-27岁",
            "温柔",
            "自然",
            "慵懒"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "71e872c2-c7de-4c72-a8d9-6e32fbc09f38",
            "age_stage": "young_adult",
            "age_min": 22,
            "age_max": 27,
            "accent": "standard-mandarin",
            "group": "女青年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "复古温润、咬字从容的青年女声，温柔怀旧，适合有声书中的复古文艺角色。",
            "vv_style": "女青年",
            "vv_tags": [
                "女青年",
                "22-27岁"
            ],
            "persona": "[#设定：女声，年龄22-27岁。强制物理级锁定复古温润中音，醇厚缓气息，彻底屏蔽现代尖细、网红甜嗓。声线复古温润，咬字舒缓从容，气质复古温柔，适合复古文艺、怀旧系少女角色。]"
        }
    },
    {
        "id": "9a647818-ada1-428d-9275-73de305d6220",
        "name": "清冷学姐",
        "language": "zh-CN",
        "gender": "female",
        "style": "高冷·沉稳·自然",
        "tags": [
            "有声书",
            "青年",
            "女青年",
            "21-25岁",
            "高冷",
            "沉稳",
            "自然"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "9a647818-ada1-428d-9275-73de305d6220",
            "age_stage": "young_adult",
            "age_min": 21,
            "age_max": 25,
            "accent": "standard-mandarin",
            "group": "女青年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "清冽薄感、咬字克制的青年女声，疏离清冷，适合有声书中的高冷学姐角色。",
            "vv_style": "女青年",
            "vv_tags": [
                "女青年",
                "21-25岁"
            ],
            "persona": "[#设定：女声，年龄21-25岁。强制物理级锁定清冽薄感中音，低气声低起伏，彻底屏蔽甜腻、尖细、亢奋。声线冷感通透，咬字利落克制，气质疏离清冷，适合冰山女神、高冷学姐角色。]"
        }
    },
    {
        "id": "7bba2337-5817-41bb-b6f4-62d076dd8811",
        "name": "古风温婉",
        "language": "zh-CN",
        "gender": "female",
        "style": "古风·温柔·自然",
        "tags": [
            "有声书",
            "青年",
            "女青年",
            "22-26岁",
            "古风",
            "温柔",
            "自然"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "7bba2337-5817-41bb-b6f4-62d076dd8811",
            "age_stage": "young_adult",
            "age_min": 22,
            "age_max": 26,
            "accent": "standard-mandarin",
            "group": "女青年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "雅致柔缓、咬字舒缓的青年女声，娴静温柔，适合有声书中的古典才女角色。",
            "vv_style": "女青年",
            "vv_tags": [
                "女青年",
                "22-26岁"
            ],
            "persona": "[#设定：女声，年龄22-26岁。强制物理级锁定雅致柔缓中音，绵长弱共鸣，彻底屏蔽尖锐、浮躁、聒噪。声线柔和婉转，咬字舒缓雅致，气质娴静温柔，适合古风闺秀、古典才女角色。]"
        }
    },
    {
        "id": "89cdaa63-352b-4818-8a46-5d300962c7d6",
        "name": "慵懒宅女",
        "language": "zh-CN",
        "gender": "female",
        "style": "慵懒·自然·温柔",
        "tags": [
            "有声书",
            "青年",
            "女青年",
            "20-25岁",
            "慵懒",
            "自然",
            "温柔"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "89cdaa63-352b-4818-8a46-5d300962c7d6",
            "age_stage": "young_adult",
            "age_min": 20,
            "age_max": 25,
            "accent": "standard-mandarin",
            "group": "女青年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "松弛微哑、咬字随性的青年女声，慵懒佛系，适合有声书中的居家宅女角色。",
            "vv_style": "女青年",
            "vv_tags": [
                "女青年",
                "20-25岁"
            ],
            "persona": "[#设定：女声，年龄20-25岁。强制物理级锁定松弛慵懒低音，微哑柔化质感，彻底屏蔽尖细、高甜、紧绷。声线松散慵懒，咬字随性拖沓，气质慵懒佛系，适合居家宅系、松弛感角色。]"
        }
    },
    {
        "id": "7b8b94f7-4a1e-4c73-b4ff-d43f454eeb7e",
        "name": "高冷御姐",
        "language": "zh-CN",
        "gender": "female",
        "style": "高冷·御姐感·霸总",
        "tags": [
            "有声书",
            "青年",
            "女青年",
            "24-29岁",
            "高冷",
            "御姐感",
            "霸总"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "7b8b94f7-4a1e-4c73-b4ff-d43f454eeb7e",
            "age_stage": "young_adult",
            "age_min": 24,
            "age_max": 29,
            "accent": "standard-mandarin",
            "group": "女青年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "冷艳紧致、咬字干脆的青年女声，强势成熟，适合有声书中的职场御姐角色。",
            "vv_style": "女青年",
            "vv_tags": [
                "女青年",
                "24-29岁"
            ],
            "persona": "[#设定：女声，年龄24-29岁。强制物理级锁定冷艳紧致中低音，无多余气声，彻底屏蔽奶气、稚嫩、娇嗲。声线干练冷冽，咬字干脆利落，气质强势成熟，适合职场御姐、女王系角色。]"
        }
    },
    {
        "id": "06a3c96a-8725-4de5-9ad5-693aede4bff6",
        "name": "空灵仙女",
        "language": "zh-CN",
        "gender": "female",
        "style": "温柔·自然·古风",
        "tags": [
            "有声书",
            "青年",
            "女青年",
            "20-24岁",
            "温柔",
            "自然",
            "古风"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "06a3c96a-8725-4de5-9ad5-693aede4bff6",
            "age_stage": "young_adult",
            "age_min": 20,
            "age_max": 24,
            "accent": "standard-mandarin",
            "group": "女青年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "缥缈轻柔、咬字浅淡的青年女声，仙气空灵，适合有声书中的幻想仙女角色。",
            "vv_style": "女青年",
            "vv_tags": [
                "女青年",
                "20-24岁"
            ],
            "persona": "[#设定：女声，年龄20-24岁。强制物理级锁定缥缈空幻轻音，浅弱悬浮气息，彻底屏蔽厚重、浑浊、外放。声线轻柔空灵，咬字浅淡朦胧，气质仙气脱俗，适合森系少女、空灵幻想角色。]"
        }
    },
    {
        "id": "00e1258a-36d0-4d37-a6be-bcebd3c93813",
        "name": "古灵精怪",
        "language": "zh-CN",
        "gender": "female",
        "style": "活泼·轻快·搞笑",
        "tags": [
            "有声书",
            "青年",
            "女青年",
            "19-23岁",
            "活泼",
            "轻快",
            "搞笑"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "00e1258a-36d0-4d37-a6be-bcebd3c93813",
            "age_stage": "young_adult",
            "age_min": 19,
            "age_max": 23,
            "accent": "standard-mandarin",
            "group": "女青年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "清脆跳脱、咬字俏皮的青年女声，活泼搞怪，适合有声书中的精灵少女角色。",
            "vv_style": "女青年",
            "vv_tags": [
                "女青年",
                "19-23岁"
            ],
            "persona": "[#设定：女声，年龄19-23岁。强制物理级锁定脆亮俏皮短音，短促灵动气息，彻底屏蔽沉闷、缓慢、成熟。声线清脆跳脱，咬字灵动俏皮，气质活泼搞怪，适合捣蛋少女、精灵系角色。]"
        }
    },
    {
        "id": "db471c3c-db72-4954-8bc0-981cdb485125",
        "name": "治愈暖音",
        "language": "zh-CN",
        "gender": "female",
        "style": "温柔·自然·沉稳",
        "tags": [
            "有声书",
            "青年",
            "女青年",
            "21-26岁",
            "温柔",
            "自然",
            "沉稳"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "db471c3c-db72-4954-8bc0-981cdb485125",
            "age_stage": "young_adult",
            "age_min": 21,
            "age_max": 26,
            "accent": "standard-mandarin",
            "group": "女青年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "温润暖心、咬字柔和的青年女声，温柔包容，适合有声书中的治愈陪伴角色。",
            "vv_style": "女青年",
            "vv_tags": [
                "女青年",
                "21-26岁"
            ],
            "persona": "[#设定：女声，年龄21-26岁。强制物理级锁定恒温暖调中音，柔和弱共鸣，彻底屏蔽尖锐、刻薄、冷感。声线温润暖心，咬字平缓柔和，气质温柔包容，适合治愈陪伴、安抚系角色。]"
        }
    },
    {
        "id": "5219a6b7-7a67-493e-b7fc-a4c020103fa0",
        "name": "中性酷妹",
        "language": "zh-CN",
        "gender": "female",
        "style": "高冷·自然·轻快",
        "tags": [
            "有声书",
            "青年",
            "女青年",
            "22-27岁",
            "高冷",
            "自然",
            "轻快"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "5219a6b7-7a67-493e-b7fc-a4c020103fa0",
            "age_stage": "young_adult",
            "age_min": 22,
            "age_max": 27,
            "accent": "standard-mandarin",
            "group": "女青年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "利落中性、咬字干脆的青年女声，帅气随性，适合有声书中的酷飒少女角色。",
            "vv_style": "女青年",
            "vv_tags": [
                "女青年",
                "22-27岁"
            ],
            "persona": "[#设定：女声，年龄22-27岁。强制物理级锁定利落中性中音，低柔无媚感，彻底屏蔽娇软、细尖、甜糯。声线干净飒爽，咬字简洁干脆，气质帅气随性，适合短发中性、酷飒少女角色。]"
        }
    },
    {
        "id": "475de826-3927-4f32-a75f-5d2dc9d7a4ed",
        "name": "娇柔千金",
        "language": "zh-CN",
        "gender": "female",
        "style": "甜美·温柔·自然",
        "tags": [
            "有声书",
            "青年",
            "女青年",
            "20-24岁",
            "甜美",
            "温柔",
            "自然"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "475de826-3927-4f32-a75f-5d2dc9d7a4ed",
            "age_stage": "young_adult",
            "age_min": 20,
            "age_max": 24,
            "accent": "standard-mandarin",
            "group": "女青年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "娇柔细腻、咬字绵软的青年女声，优雅娇贵，适合有声书中的富家千金角色。",
            "vv_style": "女青年",
            "vv_tags": [
                "女青年",
                "20-24岁"
            ],
            "persona": "[#设定：女声，年龄20-24岁。强制物理级锁定娇嗲柔绵细音，柔缓尾音延长，彻底屏蔽硬朗、粗犷、生冷。声线娇柔细腻，咬字绵软带娇，气质优雅娇贵，适合富家千金、娇系少女角色。]"
        }
    },
    {
        "id": "020980ed-7057-4426-a815-c281b8ffd60b",
        "name": "理智学霸",
        "language": "zh-CN",
        "gender": "female",
        "style": "专业·沉稳·高冷",
        "tags": [
            "有声书",
            "青年",
            "女青年",
            "21-26岁",
            "专业",
            "沉稳",
            "高冷"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "020980ed-7057-4426-a815-c281b8ffd60b",
            "age_stage": "young_adult",
            "age_min": 21,
            "age_max": 26,
            "accent": "standard-mandarin",
            "group": "女青年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "平稳知性、咬字标准的青年女声，理性沉稳，适合有声书中的高冷学霸角色。",
            "vv_style": "女青年",
            "vv_tags": [
                "女青年",
                "21-26岁"
            ],
            "persona": "[#设定：女声，年龄21-26岁。强制物理级锁定平稳知性中音，规整均匀气息，彻底屏蔽跳脱、软糯、情绪化。声线清爽冷静，咬字标准清晰，气质理性沉稳，适合高冷学霸、知性少女角色。]"
        }
    },
    {
        "id": "e1da6afa-264e-4e55-8dfc-d0b37a5d3e38",
        "name": "破碎忧感",
        "language": "zh-CN",
        "gender": "female",
        "style": "温柔",
        "tags": [
            "有声书",
            "青年",
            "女青年",
            "20-25岁",
            "温柔"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "e1da6afa-264e-4e55-8dfc-d0b37a5d3e38",
            "age_stage": "young_adult",
            "age_min": 20,
            "age_max": 25,
            "accent": "standard-mandarin",
            "group": "女青年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "",
            "vv_style": "女青年",
            "vv_tags": [
                "女青年",
                "20-25岁"
            ],
            "persona": "[#设定：女声，年龄20-25岁。强制物理级锁定浅弱淡感轻音，微颤柔缓气息，彻底屏蔽洪亮、亢奋、强势。声线单薄易碎，咬字轻缓微弱，气质忧郁温柔，适合破碎感、伤感系角色。]"
        }
    },
    {
        "id": "11b7efc0-ab1a-4f53-bb65-9c40af4a503e",
        "name": "爽朗直妹",
        "language": "zh-CN",
        "gender": "female",
        "style": "活泼·轻快·自然",
        "tags": [
            "有声书",
            "青年",
            "女青年",
            "22-27岁",
            "活泼",
            "轻快",
            "自然"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "11b7efc0-ab1a-4f53-bb65-9c40af4a503e",
            "age_stage": "young_adult",
            "age_min": 22,
            "age_max": 27,
            "accent": "standard-mandarin",
            "group": "女青年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "敞亮大方、咬字洪亮的青年女声，直率开朗，适合有声书中的爽朗闺蜜角色。",
            "vv_style": "女青年",
            "vv_tags": [
                "女青年",
                "22-27岁"
            ],
            "persona": "[#设定：女声，年龄22-27岁。强制物理级锁定开阔洪亮中高音，外放爽朗气息，彻底屏蔽细弱、怯懦、内敛。声线敞亮大方，咬字利落洪亮，气质直率开朗，适合直性子、闺蜜型角色。]"
        }
    },
    {
        "id": "b314ee96-7246-424b-b495-396bc9074349",
        "name": "优雅名媛",
        "language": "zh-CN",
        "gender": "female",
        "style": "温柔·专业·御姐感",
        "tags": [
            "有声书",
            "青年",
            "女青年",
            "23-28岁",
            "温柔",
            "专业",
            "御姐感"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "b314ee96-7246-424b-b495-396bc9074349",
            "age_stage": "young_adult",
            "age_min": 23,
            "age_max": 28,
            "accent": "standard-mandarin",
            "group": "女青年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "细腻精致、咬字舒缓的青年女声，端庄雅致，适合有声书中的优雅名媛角色。",
            "vv_style": "女青年",
            "vv_tags": [
                "女青年",
                "23-28岁"
            ],
            "persona": "[#设定：女声，年龄23-28岁。强制物理级锁定细腻精致轻中音，克制轻柔气息，彻底屏蔽粗粝、聒噪、乡土感。声线细腻优雅，咬字精致舒缓，气质端庄雅致，适合精致名媛、优雅淑女角色。]"
        }
    },
    {
        "id": "02c947ad-336f-4f70-b065-e0f52947e1df",
        "name": "港风烟嗓",
        "language": "zh-CN",
        "gender": "female",
        "style": "慵懒·磁性·性感",
        "tags": [
            "有声书",
            "青年",
            "女青年",
            "24-29岁",
            "慵懒",
            "磁性",
            "性感"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "02c947ad-336f-4f70-b065-e0f52947e1df",
            "age_stage": "young_adult",
            "age_min": 24,
            "age_max": 29,
            "accent": "standard-mandarin",
            "group": "女青年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "复古微哑、咬字慵懒的青年女声，低沉有氛围，适合有声书中的港风轻熟角色。",
            "vv_style": "女青年",
            "vv_tags": [
                "女青年",
                "24-29岁"
            ],
            "persona": "[#设定：女声，年龄24-29岁。强制物理级锁定复古微哑低音，沉缓绵长气息，彻底屏蔽尖细、奶味、清亮。声线低沉氛围感，咬字慵懒缓慢，气质复古慵懒，适合港风氛围感、轻熟少女角色。]"
        }
    },
    {
        "id": "0eb1fd61-51ac-49e8-88cb-997700de9171",
        "name": "甜美御音",
        "language": "zh-CN",
        "gender": "female",
        "style": "甜美·温柔·御姐感",
        "tags": [
            "有声书",
            "青年",
            "女青年",
            "23-27岁",
            "甜美",
            "温柔",
            "御姐感"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "0eb1fd61-51ac-49e8-88cb-997700de9171",
            "age_stage": "young_adult",
            "age_min": 23,
            "age_max": 27,
            "accent": "standard-mandarin",
            "group": "女青年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "甜而不腻、咬字流畅的青年女声，轻熟温柔，适合有声书中的甜御女主角色。",
            "vv_style": "女青年",
            "vv_tags": [
                "女青年",
                "23-27岁"
            ],
            "persona": "[#设定：女声，年龄23-27岁。强制物理级锁定甜而不腻中高音，均衡柔和气息，彻底屏蔽土甜、冷硬、厚重。声线甜美大方，咬字流畅自然，气质轻熟温柔，适合轻熟甜御、气质女主角色。]"
        }
    },
    {
        "id": "53a66cc5-2cfc-45c0-b3ee-208afe8d2b34",
        "name": "机械清冷",
        "language": "zh-CN",
        "gender": "female",
        "style": "高冷·专业·沉稳",
        "tags": [
            "有声书",
            "青年",
            "女青年",
            "21-25岁",
            "高冷",
            "专业",
            "沉稳"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "53a66cc5-2cfc-45c0-b3ee-208afe8d2b34",
            "age_stage": "young_adult",
            "age_min": 21,
            "age_max": 25,
            "accent": "standard-mandarin",
            "group": "女青年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "平直精准、咬字刻板的青年女声，机械疏离，适合有声书中的AI少女角色。",
            "vv_style": "女青年",
            "vv_tags": [
                "女青年",
                "21-25岁"
            ],
            "persona": "[#设定：女声，年龄21-25岁。强制物理级锁定平直无波动中音，均匀无感气息，彻底屏蔽情绪化、软糯、起伏。声线干净平淡，咬字精准刻板，气质机械疏离，适合AI少女、理性杀手角色。]"
        }
    },
    {
        "id": "8e6008f2-59fa-44c9-933b-38116e9368a8",
        "name": "江南软语",
        "language": "zh-CN",
        "gender": "female",
        "style": "温柔·古风·自然",
        "tags": [
            "有声书",
            "青年",
            "女青年",
            "20-24岁",
            "温柔",
            "古风",
            "自然"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "8e6008f2-59fa-44c9-933b-38116e9368a8",
            "age_stage": "young_adult",
            "age_min": 20,
            "age_max": 24,
            "accent": "standard-mandarin",
            "group": "女青年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "细软温婉、咬字轻柔的青年女声，江南柔情，适合有声书中的水乡少女角色。",
            "vv_style": "女青年",
            "vv_tags": [
                "女青年",
                "20-24岁"
            ],
            "persona": "[#设定：女声，年龄20-24岁。强制物理级锁定细软温润柔音，婉转浅气息，彻底屏蔽粗重、刚硬、洪亮。声线细软温婉，咬字婉转轻柔，气质江南柔情，适合水乡少女、温柔江南系角色。]"
        }
    },
    {
        "id": "d4d58824-7b73-4f1c-8764-9fc302fa0ece",
        "name": "热血飒姐",
        "language": "zh-CN",
        "gender": "female",
        "style": "热血·御姐感·活泼",
        "tags": [
            "有声书",
            "青年",
            "女青年",
            "23-28岁",
            "热血",
            "御姐感",
            "活泼"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "d4d58824-7b73-4f1c-8764-9fc302fa0ece",
            "age_stage": "young_adult",
            "age_min": 23,
            "age_max": 28,
            "accent": "standard-mandarin",
            "group": "女青年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "激昂利落、咬字果断的青年女声，热血飒爽，适合有声书中的格斗学姐角色。",
            "vv_style": "女青年",
            "vv_tags": [
                "女青年",
                "23-28岁"
            ],
            "persona": "[#设定：女声，年龄23-28岁。强制物理级锁定激昂利落中高音，强劲紧实气息，彻底屏蔽柔弱、轻飘、软糯。声线铿锵有力，咬字短促果断，气质热血飒爽，适合格斗少女、热血学姐角色。]"
        }
    },
    {
        "id": "9ea18c1d-2766-4e68-8e0b-f7b003588e46",
        "name": "禁欲冷音",
        "language": "zh-CN",
        "gender": "female",
        "style": "高冷·沉稳·自然",
        "tags": [
            "有声书",
            "青年",
            "女青年",
            "22-27岁",
            "高冷",
            "沉稳",
            "自然"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "9ea18c1d-2766-4e68-8e0b-f7b003588e46",
            "age_stage": "young_adult",
            "age_min": 22,
            "age_max": 27,
            "accent": "standard-mandarin",
            "group": "女青年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "清寡素净、咬字收敛的青年女声，禁欲疏离，适合有声书中的清冷禅意角色。",
            "vv_style": "女青年",
            "vv_tags": [
                "女青年",
                "22-27岁"
            ],
            "persona": "[#设定：女声，年龄22-27岁。强制物理级锁定寡欲清冷中音，极简无多余共鸣，彻底屏蔽妩媚、甜腻、外放。声线清寡素净，咬字克制收敛，气质禁欲疏离，适合清冷禅意、佛系少女角色。]"
        }
    },
    {
        "id": "d88dc765-ef4d-4637-9217-a4a30178e12f",
        "name": "铿锵侠气",
        "language": "zh-CN",
        "gender": "female",
        "style": "热血·古风·专业",
        "tags": [
            "有声书",
            "青年",
            "女青年",
            "23-28岁",
            "热血",
            "古风",
            "专业"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "d88dc765-ef4d-4637-9217-a4a30178e12f",
            "age_stage": "young_adult",
            "age_min": 23,
            "age_max": 28,
            "accent": "standard-mandarin",
            "group": "女青年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "刚劲铿锵、咬字干脆的青年女声，英气果敢，适合有声书中的江湖侠女角色。",
            "vv_style": "女青年",
            "vv_tags": [
                "女青年",
                "23-28岁"
            ],
            "persona": "[#设定：女声，年龄23-28岁。强制物理级锁定刚劲利落中高音，紧实爆发力气息，彻底屏蔽柔弱、轻飘、娇嗲。声线铿锵有力，咬字干脆铿锵，气质英气飒然，适合江湖侠女、果敢女主角色。]"
        }
    },
    {
        "id": "365bca6f-c2a7-453d-8baa-4407174b06a1",
        "name": "暖阳轻语",
        "language": "zh-CN",
        "gender": "female",
        "style": "温柔·自然·轻快",
        "tags": [
            "有声书",
            "青年",
            "女青年",
            "21-25岁",
            "温柔",
            "自然",
            "轻快"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "365bca6f-c2a7-453d-8baa-4407174b06a1",
            "age_stage": "young_adult",
            "age_min": 21,
            "age_max": 25,
            "accent": "standard-mandarin",
            "group": "女青年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "暖软轻盈、咬字自然的青年女声，向阳温柔，适合有声书中的治愈少女角色。",
            "vv_style": "女青年",
            "vv_tags": [
                "女青年",
                "21-25岁"
            ],
            "persona": "[#设定：女声，年龄21-25岁。强制物理级锁定暖调浅柔中音，蓬松柔和气息，彻底屏蔽阴冷、尖锐、压抑。声线暖软轻盈，咬字松弛自然，气质向阳温柔，适合治愈系、日常暖阳少女角色。]"
        }
    },
    {
        "id": "625335f3-4a50-4187-8b9e-8cc461c9afef",
        "name": "细柔轻语",
        "language": "zh-CN",
        "gender": "female",
        "style": "温柔·自然·感伤",
        "tags": [
            "有声书",
            "青年",
            "女青年",
            "20-23岁",
            "温柔",
            "自然",
            "感伤"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "625335f3-4a50-4187-8b9e-8cc461c9afef",
            "age_stage": "young_adult",
            "age_min": 20,
            "age_max": 23,
            "accent": "standard-mandarin",
            "group": "女青年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "纤细轻柔、咬字细缓的青年女声，内向文静，适合有声书中的安静少女角色。",
            "vv_style": "女青年",
            "vv_tags": [
                "女青年",
                "20-23岁"
            ],
            "persona": "[#设定：女声，年龄20-23岁。强制物理级锁定超细弱柔轻音，极浅微气息，彻底屏蔽洪亮、粗旷、厚重。声线纤细轻柔，咬字细缓小声，气质内向文静，适合安静社恐、内敛少女角色。]"
        }
    },
    {
        "id": "5ca15b0d-2b13-4481-978b-04066741c27b",
        "name": "明艳御姐",
        "language": "zh-CN",
        "gender": "female",
        "style": "御姐感·霸总·自然",
        "tags": [
            "有声书",
            "青年",
            "女青年",
            "24-29岁",
            "御姐感",
            "霸总",
            "自然"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "5ca15b0d-2b13-4481-978b-04066741c27b",
            "age_stage": "young_adult",
            "age_min": 24,
            "age_max": 29,
            "accent": "standard-mandarin",
            "group": "女青年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "饱满明艳、咬字从容的青年女声，大气成熟，适合有声书中的气场女主角色。",
            "vv_style": "女青年",
            "vv_tags": [
                "女青年",
                "24-29岁"
            ],
            "persona": "[#设定：女声，年龄24-29岁。强制物理级锁定饱满明艳中低音，醇厚实声质感，彻底屏蔽单薄、寡淡、稚嫩。声线饱满大气，咬字从容大气，气质明艳大方，适合成熟御姐、气场女主角色。]"
        }
    },
    {
        "id": "b08aa43f-6e49-4b6f-a75f-c481a8684f46",
        "name": "深夜独白",
        "language": "zh-CN",
        "gender": "female",
        "style": "慵懒·温柔·感伤",
        "tags": [
            "有声书",
            "青年",
            "女青年",
            "22-26岁",
            "慵懒",
            "温柔",
            "感伤"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "b08aa43f-6e49-4b6f-a75f-c481a8684f46",
            "age_stage": "young_adult",
            "age_min": 22,
            "age_max": 26,
            "accent": "standard-mandarin",
            "group": "女青年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "低沉舒缓、咬字慢柔的青年女声，静谧有氛围，适合有声书中的深夜独白角色。",
            "vv_style": "女青年",
            "vv_tags": [
                "女青年",
                "22-26岁"
            ],
            "persona": "[#设定：女声，年龄22-26岁。强制物理级锁定沉缓低吟中音，绵长慢节奏气息，彻底屏蔽急促、高亮、跳脱。声线低沉舒缓，咬字慢调温柔，气质静谧氛围感，适合深夜独白、氛围感叙事角色。]"
        }
    },
    {
        "id": "9cf7b941-917d-4081-bbc0-cc0700462980",
        "name": "灵动狐系",
        "language": "zh-CN",
        "gender": "female",
        "style": "性感·活泼·腹黑",
        "tags": [
            "有声书",
            "青年",
            "女青年",
            "21-25岁",
            "性感",
            "活泼",
            "腹黑"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "9cf7b941-917d-4081-bbc0-cc0700462980",
            "age_stage": "young_adult",
            "age_min": 21,
            "age_max": 25,
            "accent": "standard-mandarin",
            "group": "女青年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "灵动带媚、咬字婉转的青年女声，狡黠魅惑，适合有声书中的狐系小妖角色。",
            "vv_style": "女青年",
            "vv_tags": [
                "女青年",
                "21-25岁"
            ],
            "persona": "[#设定：女声，年龄21-25岁。强制物理级锁定狡黠灵动柔音，婉转巧气息，彻底屏蔽呆板、木讷、厚重。声线灵动带媚，咬字轻巧婉转，气质狡黠魅惑，适合狐系少女、灵动小妖角色。]"
        }
    },
    {
        "id": "a60c05a4-2f08-4199-b4ac-1cc899c81254",
        "name": "质朴乡土",
        "language": "zh-CN",
        "gender": "female",
        "style": "自然·温柔·沉稳",
        "tags": [
            "有声书",
            "青年",
            "女青年",
            "20-26岁",
            "自然",
            "温柔",
            "沉稳"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "a60c05a4-2f08-4199-b4ac-1cc899c81254",
            "age_stage": "young_adult",
            "age_min": 20,
            "age_max": 26,
            "accent": "standard-mandarin",
            "group": "女青年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "平实淳朴、咬字自然的青年女声，善良接地气，适合有声书中的乡村少女角色。",
            "vv_style": "女青年",
            "vv_tags": [
                "女青年",
                "20-26岁"
            ],
            "persona": "[#设定：女声，年龄20-26岁。强制物理级锁定平实淳朴中音，自然原生态气息，彻底屏蔽精致、刻意、娇柔。声线朴实接地气，咬字直白自然，气质淳朴善良，适合乡村少女、质朴女孩角色。]"
        }
    },
    {
        "id": "a2b82b12-af04-4772-b65d-2424726b743c",
        "name": "薄纱空灵",
        "language": "zh-CN",
        "gender": "female",
        "style": "温柔·自然·感伤",
        "tags": [
            "有声书",
            "青年",
            "女青年",
            "19-23岁",
            "温柔",
            "自然",
            "感伤"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "a2b82b12-af04-4772-b65d-2424726b743c",
            "age_stage": "young_adult",
            "age_min": 19,
            "age_max": 23,
            "accent": "standard-mandarin",
            "group": "女青年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "轻薄通透、咬字朦胧的青年女声，空灵脱俗，适合有声书中的梦境少女角色。",
            "vv_style": "女青年",
            "vv_tags": [
                "女青年",
                "19-23岁"
            ],
            "persona": "[#设定：女声，年龄19-23岁。强制物理级锁定薄纱轻透幻音，悬浮式浅气息，彻底屏蔽实沉、厚重、浑浊。声线轻薄通透，咬字虚浅朦胧，气质空灵脱俗，适合幻境少女、梦境系角色。]"
        }
    },
    {
        "id": "0ecfeee5-ca48-4548-b290-697dd38e1f84",
        "name": "利落职场",
        "language": "zh-CN",
        "gender": "female",
        "style": "专业·沉稳·高冷",
        "tags": [
            "有声书",
            "青年",
            "女青年",
            "23-28岁",
            "专业",
            "沉稳",
            "高冷"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "0ecfeee5-ca48-4548-b290-697dd38e1f84",
            "age_stage": "young_adult",
            "age_min": 23,
            "age_max": 28,
            "accent": "standard-mandarin",
            "group": "女青年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "标准干练、咬字精准的青年女声，理性专业，适合有声书中的职场白领角色。",
            "vv_style": "女青年",
            "vv_tags": [
                "女青年",
                "23-28岁"
            ],
            "persona": "[#设定：女声，年龄23-28岁。强制物理级锁定标准干练中音，平稳规整气息，彻底屏蔽情绪化、软糯、散漫。声线清晰利落，咬字精准干练，气质理性专业，适合职场白领、商务女声角色。]"
        }
    },
    {
        "id": "2ddc3d04-1252-4dc8-8f05-2f5a7ab0c6d3",
        "name": "哀怨古风",
        "language": "zh-CN",
        "gender": "female",
        "style": "古风·感伤·温柔",
        "tags": [
            "有声书",
            "青年",
            "女青年",
            "21-25岁",
            "古风",
            "感伤",
            "温柔"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "2ddc3d04-1252-4dc8-8f05-2f5a7ab0c6d3",
            "age_stage": "young_adult",
            "age_min": 21,
            "age_max": 25,
            "accent": "standard-mandarin",
            "group": "女青年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "凄婉轻柔、咬字含忧的青年女声，哀怨温婉，适合有声书中的古风悲情角色。",
            "vv_style": "女青年",
            "vv_tags": [
                "女青年",
                "21-25岁"
            ],
            "persona": "[#设定：女声，年龄21-25岁。强制物理级锁定凄婉哀怨柔音，微颤绵长气息，彻底屏蔽欢快、外放、爽朗。声线凄婉轻柔，咬字缓慢含忧，气质凄美温婉，适合古风悲情、闺中女子角色。]"
        }
    },
    {
        "id": "dec6c87f-be0a-41ce-becd-4c9044771057",
        "name": "清亮百灵",
        "language": "zh-CN",
        "gender": "female",
        "style": "活泼·轻快·自然",
        "tags": [
            "有声书",
            "青年",
            "女青年",
            "19-22岁",
            "活泼",
            "轻快",
            "自然"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "dec6c87f-be0a-41ce-becd-4c9044771057",
            "age_stage": "young_adult",
            "age_min": 19,
            "age_max": 22,
            "accent": "standard-mandarin",
            "group": "女青年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "清亮悦耳、咬字透亮的青年女声，明媚鲜活，适合有声书中的百灵少女角色。",
            "vv_style": "女青年",
            "vv_tags": [
                "女青年",
                "19-22岁"
            ],
            "persona": "[#设定：女声，年龄19-22岁。强制物理级锁定高亮通透脆音，开阔干净气息，彻底屏蔽闷沉、沙哑、压抑。声线清亮悦耳，咬字轻快透亮，气质明媚鲜活，适合歌唱系、百灵少女角色。]"
        }
    },
    {
        "id": "4cebccb3-f681-404d-b195-a5e220495f15",
        "name": "慵懒冷御",
        "language": "zh-CN",
        "gender": "female",
        "style": "慵懒·高冷·御姐感",
        "tags": [
            "有声书",
            "青年",
            "女青年",
            "24-28岁",
            "慵懒",
            "高冷",
            "御姐感"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "4cebccb3-f681-404d-b195-a5e220495f15",
            "age_stage": "young_adult",
            "age_min": 24,
            "age_max": 28,
            "accent": "standard-mandarin",
            "group": "女青年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "冷感松弛、咬字散漫的青年女声，慵懒高冷，适合有声书中的轻熟冷御角色。",
            "vv_style": "女青年",
            "vv_tags": [
                "女青年",
                "24-28岁"
            ],
            "persona": "[#设定：女声，年龄24-28岁。强制物理级锁定冷感慵懒中低音，松弛克制气息，彻底屏蔽亢奋、娇嗲、甜软。声线冷松结合，咬字散漫冷淡，气质慵懒高冷，适合高冷宅御、轻熟少女角色。]"
        }
    },
    {
        "id": "e1f5c15f-cabf-4540-a910-fc174bb5a4a7",
        "name": "书卷清冷",
        "language": "zh-CN",
        "gender": "female",
        "style": "高冷·专业·自然",
        "tags": [
            "有声书",
            "青年",
            "女青年",
            "22-26岁",
            "高冷",
            "专业",
            "自然"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "e1f5c15f-cabf-4540-a910-fc174bb5a4a7",
            "age_stage": "young_adult",
            "age_min": 22,
            "age_max": 26,
            "accent": "standard-mandarin",
            "group": "女青年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "沉静素雅、咬字克制的青年女声，书香清冷，适合有声书中的文艺学者角色。",
            "vv_style": "女青年",
            "vv_tags": [
                "女青年",
                "22-26岁"
            ],
            "persona": "[#设定：女声，年龄22-26岁。强制物理级锁定文雅清冷中音，沉静低气息，彻底屏蔽聒噪、艳丽、甜俗。声线沉静素雅，咬字平缓克制，气质书香清冷，适合文艺作家、安静学者角色。]"
        }
    },
    {
        "id": "bbef2646-f5a8-42af-bf62-08292a60f24c",
        "name": "元气甜嗓",
        "language": "zh-CN",
        "gender": "female",
        "style": "活泼·轻快·甜美",
        "tags": [
            "有声书",
            "青年",
            "女青年",
            "20-23岁",
            "活泼",
            "轻快",
            "甜美"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "bbef2646-f5a8-42af-bf62-08292a60f24c",
            "age_stage": "young_adult",
            "age_min": 20,
            "age_max": 23,
            "accent": "standard-mandarin",
            "group": "女青年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "清甜鲜活、咬字轻快的青年女声，元气饱满，适合有声书中的活力少女角色。",
            "vv_style": "女青年",
            "vv_tags": [
                "女青年",
                "20-23岁"
            ],
            "persona": "[#设定：女声，年龄20-23岁。强制物理级锁定鲜活清甜高音，饱满活力气息，彻底屏蔽低沉、丧感、慵懒。声线清甜鲜活，咬字轻快饱满，气质元气满满，适合短视频、活力解说角色。]"
        }
    },
    {
        "id": "ce51b32b-73bf-4486-8054-97d0b147958e",
        "name": "深夜电台",
        "language": "zh-CN",
        "gender": "female",
        "style": "慵懒·温柔·磁性",
        "tags": [
            "有声书",
            "青年",
            "女青年",
            "23-27岁",
            "慵懒",
            "温柔",
            "磁性"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "ce51b32b-73bf-4486-8054-97d0b147958e",
            "age_stage": "young_adult",
            "age_min": 23,
            "age_max": 27,
            "accent": "standard-mandarin",
            "group": "女青年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "浅哑温柔、咬字缓慢的青年女声，氛围松弛，适合有声书中的深夜电台角色。",
            "vv_style": "女青年",
            "vv_tags": [
                "女青年",
                "23-27岁"
            ],
            "persona": "[#设定：女声，年龄23-27岁。强制物理级锁定浅哑柔润中音，薄雾质感气息，彻底屏蔽尖锐、透亮、高甜。声线微哑温柔，咬字松弛缓慢，气质温柔氛围感，适合深夜电台、情感讲述角色。]"
        }
    },
    {
        "id": "be1dc9d9-bba6-4e00-9a17-638836a42e3b",
        "name": "洒脱江湖",
        "language": "zh-CN",
        "gender": "female",
        "style": "古风·热血·自然",
        "tags": [
            "有声书",
            "青年",
            "女青年",
            "22-27岁",
            "古风",
            "热血",
            "自然"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "be1dc9d9-bba6-4e00-9a17-638836a42e3b",
            "age_stage": "young_adult",
            "age_min": 22,
            "age_max": 27,
            "accent": "standard-mandarin",
            "group": "女青年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "随性敞亮、咬字自然的青年女声，江湖洒脱，适合有声书中的自由女侠角色。",
            "vv_style": "女青年",
            "vv_tags": [
                "女青年",
                "22-27岁"
            ],
            "persona": "[#设定：女声，年龄22-27岁。强制物理级锁定随性开阔中音，自由舒展气息，彻底屏蔽拘谨、娇弱、狭隘。声线洒脱敞亮，咬字随性自然，气质江湖随性，适合洒脱女侠、自由少女角色。]"
        }
    },
    {
        "id": "256c9602-7356-4e72-8398-165ba007788a",
        "name": "温厚大叔",
        "language": "zh-CN",
        "gender": "male",
        "style": "温柔·沉稳·磁性",
        "tags": [
            "有声书",
            "中年",
            "男中年",
            "35-45岁",
            "温柔",
            "沉稳",
            "磁性"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "256c9602-7356-4e72-8398-165ba007788a",
            "age_stage": "mature",
            "age_min": 35,
            "age_max": 45,
            "accent": "standard-mandarin",
            "group": "男中年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "醇厚暖调、咬字平缓的中年男声，亲切可靠，适合有声书中的暖心长辈角色。",
            "vv_style": "男中年",
            "vv_tags": [
                "男中年",
                "35-45岁"
            ],
            "persona": "[#设定：男声，年龄35-45岁。强制物理级锁定醇厚暖调中低音，饱满柔和共鸣，彻底割据尖锐、轻浮、冷硬。声线沉稳厚实，咬字平缓温和，气质亲切可靠，适合邻家长辈、暖心大叔角色。]"
        }
    },
    {
        "id": "3f820825-638e-475d-9fea-3778559658e1",
        "name": "沧桑烟嗓",
        "language": "zh-CN",
        "gender": "male",
        "style": "磁性·慵懒·感伤",
        "tags": [
            "有声书",
            "中年",
            "男中年",
            "38-52岁",
            "磁性",
            "慵懒",
            "感伤"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "3f820825-638e-475d-9fea-3778559658e1",
            "age_stage": "mature",
            "age_min": 38,
            "age_max": 52,
            "accent": "standard-mandarin",
            "group": "男中年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "低沉烟嗓、咬字厚重的中年男声，沧桑有故事感，适合有声书中的阅历长辈角色。",
            "vv_style": "男中年",
            "vv_tags": [
                "男中年",
                "38-52岁"
            ],
            "persona": "[#设定：男声，年龄38-52岁。强制物理级锁定岁月磨砂烟感低音，浅哑颗粒质感，彻底屏蔽清亮、稚嫩、单薄。声线沧桑低沉，咬字缓慢厚重，气质故事感十足，适合成熟浪子、阅历长辈角色。]"
        }
    },
    {
        "id": "9904a969-1ded-4ebb-bdfa-51e8b0bfdd66",
        "name": "儒雅文叔",
        "language": "zh-CN",
        "gender": "male",
        "style": "专业·温柔·沉稳",
        "tags": [
            "有声书",
            "中年",
            "男中年",
            "34-44岁",
            "专业",
            "温柔",
            "沉稳"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "9904a969-1ded-4ebb-bdfa-51e8b0bfdd66",
            "age_stage": "mature",
            "age_min": 34,
            "age_max": 44,
            "accent": "standard-mandarin",
            "group": "男中年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "温润文雅、咬字规整的中年男声，谦和儒雅，适合有声书中的文人学者角色。",
            "vv_style": "男中年",
            "vv_tags": [
                "男中年",
                "34-44岁"
            ],
            "persona": "[#设定：男声，年龄34-44岁。强制物理级锁定温润雅致中音，平缓绵长气息，彻底屏蔽粗粝、暴躁、外放。声线文雅柔和，咬字规整舒缓，气质谦和儒雅，适合文人学者、斯文绅士角色。]"
        }
    },
    {
        "id": "04e42db9-9838-4646-8471-afde6f18995f",
        "name": "沉稳大叔",
        "language": "zh-CN",
        "gender": "male",
        "style": "沉稳·霸总·磁性",
        "tags": [
            "有声书",
            "中年",
            "男中年",
            "37-49岁",
            "沉稳",
            "霸总",
            "磁性"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "04e42db9-9838-4646-8471-afde6f18995f",
            "age_stage": "mature",
            "age_min": 37,
            "age_max": 49,
            "accent": "standard-mandarin",
            "group": "男中年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "厚重有力、咬字果断的中年男声，强势威严，适合有声书中的成熟掌权者角色。",
            "vv_style": "男中年",
            "vv_tags": [
                "男中年",
                "37-49岁"
            ],
            "persona": "[#设定：男声，年龄37-49岁。强制物理级锁定雄厚力量低音，紧实深共鸣，彻底屏蔽软弱、细弱、轻飘。声线厚重有力，咬字沉稳果断，气质强势威严，适合企业高管、成熟掌权者角色。]"
        }
    },
    {
        "id": "279dccc6-0545-490d-b6c4-1c00c514e6d3",
        "name": "慵懒大叔",
        "language": "zh-CN",
        "gender": "male",
        "style": "慵懒·磁性·性感",
        "tags": [
            "有声书",
            "中年",
            "男中年",
            "35-46岁",
            "慵懒",
            "磁性",
            "性感"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "279dccc6-0545-490d-b6c4-1c00c514e6d3",
            "age_stage": "mature",
            "age_min": 35,
            "age_max": 46,
            "accent": "standard-mandarin",
            "group": "男中年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "松弛磁性、咬字缓慢的中年男声，慵懒魅惑，适合有声书中的氛围感大叔角色。",
            "vv_style": "男中年",
            "vv_tags": [
                "男中年",
                "35-46岁"
            ],
            "persona": "[#设定：男声，年龄35-46岁。强制物理级锁定松弛慵懒低中音，散漫轻气息，彻底屏蔽紧绷、急促、硬朗。声线松弛磁性，咬字随性缓慢，气质慵懒魅惑，适合轻熟魅力、氛围感大叔角色。]"
        }
    },
    {
        "id": "e04e855e-4958-467a-ac32-efc639ede2b2",
        "name": "爽朗硬汉",
        "language": "zh-CN",
        "gender": "male",
        "style": "热血·活泼·自然",
        "tags": [
            "有声书",
            "中年",
            "男中年",
            "39-53岁",
            "热血",
            "活泼",
            "自然"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "e04e855e-4958-467a-ac32-efc639ede2b2",
            "age_stage": "mature",
            "age_min": 39,
            "age_max": 53,
            "accent": "standard-mandarin",
            "group": "男中年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "铿锵敞亮、咬字利落的中年男声，豪迈直爽，适合有声书中的硬汉军人角色。",
            "vv_style": "男中年",
            "vv_tags": [
                "男中年",
                "39-53岁"
            ],
            "persona": "[#设定：男声，年龄39-53岁。强制物理级锁定开阔洪亮中音，扎实外放气息，彻底屏蔽阴柔、沙哑、压抑。声线铿锵敞亮，咬字干脆利落，气质豪迈直爽，适合硬汉军人、户外硬汉角色。]"
        }
    },
    {
        "id": "393a0a76-ee26-4911-8025-db69f667b39a",
        "name": "古风隐士",
        "language": "zh-CN",
        "gender": "male",
        "style": "古风·沉稳·自然",
        "tags": [
            "有声书",
            "中年",
            "男中年",
            "40-55岁",
            "古风",
            "沉稳",
            "自然"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "393a0a76-ee26-4911-8025-db69f667b39a",
            "age_stage": "mature",
            "age_min": 40,
            "age_max": 55,
            "accent": "standard-mandarin",
            "group": "男中年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "清和淡远、咬字从容的中年男声，仙风道骨，适合有声书中的古风隐士角色。",
            "vv_style": "男中年",
            "vv_tags": [
                "男中年",
                "40-55岁"
            ],
            "persona": "[#设定：男声，年龄40-55岁。强制物理级锁定清和淡远中音，空灵平缓气息，彻底屏蔽市侩、油腻、强势。声线淡然悠远，咬字从容舒缓，气质仙风道骨，适合隐世高人、古风道长角色。]"
        }
    },
    {
        "id": "5b4dee34-269e-465f-9c4f-1d6e9331a317",
        "name": "磁性大叔",
        "language": "zh-CN",
        "gender": "male",
        "style": "磁性·沉稳·自然",
        "tags": [
            "有声书",
            "中年",
            "男中年",
            "34-45岁",
            "磁性",
            "沉稳",
            "自然"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "5b4dee34-269e-465f-9c4f-1d6e9331a317",
            "age_stage": "mature",
            "age_min": 34,
            "age_max": 45,
            "accent": "standard-mandarin",
            "group": "男中年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "沉稳厚实、富有磁性的中年男声，胸腔共鸣清晰，适合有声书成熟角色。",
            "vv_style": "男中年",
            "vv_tags": [
                "男中年",
                "34-45岁"
            ],
            "persona": "[#设定：男声，年龄34-45岁。强制物理级锁定厚密气泡低音，绵密低沉共鸣，彻底屏蔽尖细、干涩、高亮。声线磁性浓郁，咬字低沉舒缓，气质成熟魅惑，适合情感叙事、治愈陪伴角色。]"
        }
    },
    {
        "id": "1b37d94c-b207-44c4-bccd-53e225ccc4c4",
        "name": "严厉严父",
        "language": "zh-CN",
        "gender": "male",
        "style": "专业·沉稳·霸总",
        "tags": [
            "有声书",
            "中年",
            "男中年",
            "36-47岁",
            "专业",
            "沉稳",
            "霸总"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "1b37d94c-b207-44c4-bccd-53e225ccc4c4",
            "age_stage": "mature",
            "age_min": 36,
            "age_max": 47,
            "accent": "standard-mandarin",
            "group": "男中年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "刚硬端正、咬字严肃的中年男声，威严克制，适合有声书中的严父长官角色。",
            "vv_style": "男中年",
            "vv_tags": [
                "男中年",
                "36-47岁"
            ],
            "persona": "[#设定：男声，年龄36-47岁。强制物理级锁定刚硬端正中音，紧实严肃声质，彻底屏蔽散漫、软糯、轻佻。声线端正有力，咬字严肃规整，气质威严端正，适合严系父辈、纪律长官角色。]"
        }
    },
    {
        "id": "2681e26c-720c-41cf-a5fb-3722145a7f41",
        "name": "市井火叔",
        "language": "zh-CN",
        "gender": "male",
        "style": "自然·活泼·搞笑",
        "tags": [
            "有声书",
            "中年",
            "男中年",
            "38-50岁",
            "自然",
            "活泼",
            "搞笑"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "2681e26c-720c-41cf-a5fb-3722145a7f41",
            "age_stage": "mature",
            "age_min": 38,
            "age_max": 50,
            "accent": "standard-mandarin",
            "group": "男中年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "朴实自然、咬字直白的中年男声，烟火气浓厚，适合有声书中的市井老板角色。",
            "vv_style": "男中年",
            "vv_tags": [
                "男中年",
                "38-50岁"
            ],
            "persona": "[#设定：男声，年龄38-50岁。强制物理级锁定平实淳朴中低音，自然接地气声感，彻底屏蔽精致、冷感、刻意。声线朴实自然，咬字直白松弛，气质烟火气浓厚，适合市井老板、普通长辈角色。]"
        }
    },
    {
        "id": "abcbebbb-7434-4e29-b21c-752c48b8c677",
        "name": "温柔大叔",
        "language": "zh-CN",
        "gender": "male",
        "style": "温柔·沉稳·自然",
        "tags": [
            "有声书",
            "中年",
            "男中年",
            "33-43岁",
            "温柔",
            "沉稳",
            "自然"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "abcbebbb-7434-4e29-b21c-752c48b8c677",
            "age_stage": "mature",
            "age_min": 33,
            "age_max": 43,
            "accent": "standard-mandarin",
            "group": "男中年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "柔润暖心、咬字轻缓的中年男声，包容温柔，适合有声书中的疏导陪伴角色。",
            "vv_style": "男中年",
            "vv_tags": [
                "男中年",
                "33-43岁"
            ],
            "persona": "[#设定：男声，年龄33-43岁。强制物理级锁定柔润暖调中低音，轻柔弱共鸣，彻底屏蔽冷厉、沙哑、厚重压迫感。声线柔和暖心，咬字轻缓舒缓，气质包容温柔，适合心理疏导、深夜哄读角色。]"
        }
    },
    {
        "id": "0ce83abd-dd06-4773-8e2e-0a6a1a080d0c",
        "name": "腹黑大叔",
        "language": "zh-CN",
        "gender": "male",
        "style": "腹黑·磁性·高冷",
        "tags": [
            "有声书",
            "中年",
            "男中年",
            "37-49岁",
            "腹黑",
            "磁性",
            "高冷"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "0ce83abd-dd06-4773-8e2e-0a6a1a080d0c",
            "age_stage": "mature",
            "age_min": 37,
            "age_max": 49,
            "accent": "standard-mandarin",
            "group": "男中年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "暗哑低沉、咬字迂回的中年男声，腹黑神秘，适合有声书中的城府反派角色。",
            "vv_style": "男中年",
            "vv_tags": [
                "男中年",
                "37-49岁"
            ],
            "persona": "[#设定：男声，年龄37-49岁。强制物理级锁定暗哑魅惑低音，婉转沉缓气息，彻底屏蔽正气、直白、爽朗。声线暗哑带魅，咬字迂回缓慢，气质腹黑神秘，适合腹黑反派、城府角色。]"
        }
    },
    {
        "id": "e3758337-ae74-4cb3-8c6b-e28814c191ed",
        "name": "豪迈大叔",
        "language": "zh-CN",
        "gender": "male",
        "style": "热血·古风·自然",
        "tags": [
            "有声书",
            "中年",
            "男中年",
            "40-54岁",
            "热血",
            "古风",
            "自然"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "e3758337-ae74-4cb3-8c6b-e28814c191ed",
            "age_stage": "mature",
            "age_min": 40,
            "age_max": 54,
            "accent": "standard-mandarin",
            "group": "男中年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "雄浑开阔、咬字洒脱的中年男声，江湖豪迈，适合有声书中的豪爽前辈角色。",
            "vv_style": "男中年",
            "vv_tags": [
                "男中年",
                "40-54岁"
            ],
            "persona": "[#设定：男声，年龄40-54岁。强制物理级锁定雄浑开阔中低音，充沛舒展气息，彻底屏蔽拘谨、阴柔、细碎。声线雄浑大气，咬字豪迈洒脱，气质江湖义气，适合江湖豪杰、豪爽前辈角色。]"
        }
    },
    {
        "id": "716ded4a-9b50-44ed-9231-364107cce7fd",
        "name": "忧郁大叔",
        "language": "zh-CN",
        "gender": "male",
        "style": "感伤·沉稳·磁性",
        "tags": [
            "有声书",
            "中年",
            "男中年",
            "39-51岁",
            "感伤",
            "沉稳",
            "磁性"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "716ded4a-9b50-44ed-9231-364107cce7fd",
            "age_stage": "mature",
            "age_min": 39,
            "age_max": 51,
            "accent": "standard-mandarin",
            "group": "男中年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "沉郁微哑、咬字缓慢的中年男声，内敛忧郁，适合有声书中的心事沉重角色。",
            "vv_style": "男中年",
            "vv_tags": [
                "男中年",
                "39-51岁"
            ],
            "persona": "[#设定：男声，年龄39-51岁。强制物理级锁定沉郁微哑低音，压抑绵长气息，彻底屏蔽明亮、亢奋、外放。声线沉哑内敛，咬字低沉缓慢，气质内敛忧郁，适合心事沉重、内敛成熟角色。]"
        }
    },
    {
        "id": "e954d365-a8c0-463f-b40d-61c6ddacd7e4",
        "name": "儒雅大叔",
        "language": "zh-CN",
        "gender": "male",
        "style": "专业·温柔·自然",
        "tags": [
            "有声书",
            "中年",
            "男中年",
            "33-42岁",
            "专业",
            "温柔",
            "自然"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "e954d365-a8c0-463f-b40d-61c6ddacd7e4",
            "age_stage": "mature",
            "age_min": 33,
            "age_max": 42,
            "accent": "standard-mandarin",
            "group": "男中年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "通透干净、咬字清晰的中年男声，轻熟儒雅，适合有声书中的清爽前辈角色。",
            "vv_style": "男中年",
            "vv_tags": [
                "男中年",
                "33-42岁"
            ],
            "persona": "[#设定：男声，年龄33-42岁。强制物理级锁定通透干净中低音，无杂质清爽质感，彻底屏蔽油腻、沧桑、厚重。声线干净利落，咬字清晰轻快，气质轻熟清爽，适合轻熟型男、儒雅前辈角色。]"
        }
    },
    {
        "id": "f436b89f-5f19-44d7-b44b-0f7ed655382c",
        "name": "港风大叔",
        "language": "zh-CN",
        "gender": "male",
        "style": "慵懒·磁性·自然",
        "tags": [
            "有声书",
            "中年",
            "男中年",
            "38-50岁",
            "慵懒",
            "磁性",
            "自然"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "f436b89f-5f19-44d7-b44b-0f7ed655382c",
            "age_stage": "mature",
            "age_min": 38,
            "age_max": 50,
            "accent": "standard-mandarin",
            "group": "男中年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "复古柔哑、咬字从容的中年男声，怀旧温润，适合有声书中的港风叙事角色。",
            "vv_style": "男中年",
            "vv_tags": [
                "男中年",
                "38-50岁"
            ],
            "persona": "[#设定：男声，年龄38-50岁。强制物理级锁定复古柔哑中低音，怀旧绵长气息，彻底屏蔽现代尖硬、高亮生硬感。声线复古温润，咬字慵懒从容，气质怀旧氛围感，适合港风怀旧、复古叙事角色。]"
        }
    },
    {
        "id": "abe5b2eb-d2c9-47a1-926e-ab5e10a4a425",
        "name": "厚腔熟女",
        "language": "zh-CN",
        "gender": "female",
        "style": "沉稳·磁性·御姐感",
        "tags": [
            "有声书",
            "中年",
            "女中年",
            "36-48岁",
            "沉稳",
            "磁性",
            "御姐感"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "abe5b2eb-d2c9-47a1-926e-ab5e10a4a425",
            "age_stage": "mature",
            "age_min": 36,
            "age_max": 48,
            "accent": "standard-mandarin",
            "group": "女中年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "厚重低沉、咬字饱满的中年女声，大气压场，适合有声书中的强势熟女角色。",
            "vv_style": "女中年",
            "vv_tags": [
                "女中年",
                "36-48岁"
            ],
            "persona": "[#设定：女声，年龄36-48岁。强制物理级锁定女中低厚腔基底，下沉胸腔低位发声，高密度实声，彻底屏蔽头腔高音、薄细声、气声虚感。声基底厚重低沉，声压沉稳，咬字沉实饱满，气质大气压场，属于低频大嗓成熟女声。]"
        }
    },
    {
        "id": "4a226c2b-52f7-4a45-a6f9-d6410191fce9",
        "name": "尖腔熟女",
        "language": "zh-CN",
        "gender": "female",
        "style": "霸总·高冷·专业",
        "tags": [
            "有声书",
            "中年",
            "女中年",
            "32-43岁",
            "霸总",
            "高冷",
            "专业"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "4a226c2b-52f7-4a45-a6f9-d6410191fce9",
            "age_stage": "mature",
            "age_min": 32,
            "age_max": 43,
            "accent": "standard-mandarin",
            "group": "女中年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "尖细锐利、咬字紧促的中年女声，强势锋利，适合有声书中的尖锐熟女角色。",
            "vv_style": "女中年",
            "vv_tags": [
                "女中年",
                "32-43岁"
            ],
            "persona": "[#设定：女声，年龄32-43岁。强制物理级锁定女中尖腔高位发声，窄频紧束高音基底，彻底屏蔽低频厚嗓、松弛声、圆润共鸣。声基底尖细锐利，穿透力极强，咬字紧促锋利，气质强势尖锐，高频刺耳型中年女声。]"
        }
    },
    {
        "id": "8dbba85b-5abf-4739-8e13-e9fed6061902",
        "name": "虚靡熟女",
        "language": "zh-CN",
        "gender": "female",
        "style": "感伤·慵懒·温柔",
        "tags": [
            "有声书",
            "中年",
            "女中年",
            "34-45岁",
            "感伤",
            "慵懒",
            "温柔"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "8dbba85b-5abf-4739-8e13-e9fed6061902",
            "age_stage": "mature",
            "age_min": 34,
            "age_max": 45,
            "accent": "standard-mandarin",
            "group": "女中年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "虚软带息、咬字轻虚的中年女声，缥缈虚弱，适合有声书中的病弱熟女角色。",
            "vv_style": "女中年",
            "vv_tags": [
                "女中年",
                "34-45岁"
            ],
            "persona": "[#设定：女声，年龄34-45岁。强制物理级锁定半气声虚化基底，口鼻浅息弱发声，彻底屏蔽全实声、强声压、胸腔厚共鸣。声基底虚软缥缈，实声占比极低，咬字轻虚带息，气质虚弱靡感，弱气轻嗓中年女。]"
        }
    },
    {
        "id": "64cd95fd-36fb-4b0d-9951-48e1a871964c",
        "name": "烟嗓熟女",
        "language": "zh-CN",
        "gender": "female",
        "style": "磁性·慵懒·感伤",
        "tags": [
            "有声书",
            "中年",
            "女中年",
            "38-50岁",
            "磁性",
            "慵懒",
            "感伤"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "64cd95fd-36fb-4b0d-9951-48e1a871964c",
            "age_stage": "mature",
            "age_min": 38,
            "age_max": 50,
            "accent": "standard-mandarin",
            "group": "女中年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "低哑粗粝、咬字随性的中年女声，沧桑风尘，适合有声书中的烟火熟女角色。",
            "vv_style": "女中年",
            "vv_tags": [
                "女中年",
                "38-50岁"
            ],
            "persona": "[#设定：女声，年龄38-50岁。强制物理级锁定声带摩擦磨砂基底，原生粗哑烟感频段，彻底屏蔽顺滑嫩嗓、干净亮音、精致声质。声基底自带颗粒糙感，低哑沧桑，咬字随性粗粝，气质风尘厚重，粗嗓烟火中年女声。]"
        }
    },
    {
        "id": "66b0f41c-36e3-4d90-8227-b0b81d6b22f0",
        "name": "亮透熟声",
        "language": "zh-CN",
        "gender": "female",
        "style": "专业·轻快·自然",
        "tags": [
            "有声书",
            "中年",
            "女中年",
            "30-40岁",
            "专业",
            "轻快",
            "自然"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "66b0f41c-36e3-4d90-8227-b0b81d6b22f0",
            "age_stage": "mature",
            "age_min": 30,
            "age_max": 40,
            "accent": "standard-mandarin",
            "group": "女中年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "清亮通透、咬字利落的中年女声，清爽干练，适合有声书中的亮嗓熟龄角色。",
            "vv_style": "女中年",
            "vv_tags": [
                "女中年",
                "30-40岁"
            ],
            "persona": "[#设定：女声，年龄30-40岁。强制物理级锁定头腔集中发声基底，高通透高频段，彻底屏蔽胸腔低音、闷浊感、沙哑颗粒。声基底清亮干净，泛音通透，咬字轻快利落，气质清爽干练，亮嗓轻熟中年女。]"
        }
    },
    {
        "id": "84477e62-c03c-4b2a-a397-8d814468e965",
        "name": "厚重闷音",
        "language": "zh-CN",
        "gender": "female",
        "style": "沉稳·感伤·磁性",
        "tags": [
            "有声书",
            "中年",
            "女中年",
            "37-49岁",
            "沉稳",
            "感伤",
            "磁性"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "84477e62-c03c-4b2a-a397-8d814468e965",
            "age_stage": "mature",
            "age_min": 37,
            "age_max": 49,
            "accent": "standard-mandarin",
            "group": "女中年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "闷沉内敛、咬字滞缓的中年女声，压抑深沉，适合有声书中的沉郁女性角色。",
            "vv_style": "女中年",
            "vv_tags": [
                "女中年",
                "37-49岁"
            ],
            "persona": "[#设定：女声，年龄37-49岁。强制物理级锁定喉位下压闷声基底，封闭内收低频，彻底屏蔽开放敞亮、外放共鸣、高位置发声。声基底闷沉内敛，音色偏暗，咬字含收滞缓，气质压抑深沉，闷嗓内敛中年女声。]"
        }
    },
    {
        "id": "1973cd81-c261-4162-a49a-a00f0371f8b3",
        "name": "扁嗓中性",
        "language": "zh-CN",
        "gender": "female",
        "style": "高冷·自然·沉稳",
        "tags": [
            "有声书",
            "中年",
            "女中年",
            "33-44岁",
            "高冷",
            "自然",
            "沉稳"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "1973cd81-c261-4162-a49a-a00f0371f8b3",
            "age_stage": "mature",
            "age_min": 33,
            "age_max": 44,
            "accent": "standard-mandarin",
            "group": "女中年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "扁平直白、咬字生硬的中年女声，中性冷漠，适合有声书中的平板寡言角色。",
            "vv_style": "女中年",
            "vv_tags": [
                "女中年",
                "33-44岁"
            ],
            "persona": "[#设定：女声，年龄33-44岁。强制物理级锁定扁形平板发声基底，无起伏无共鸣平频，彻底屏蔽婉转柔腔、立体共鸣、情绪起伏。声基底扁平直白，音色寡淡，咬字平直生硬，气质中性冷漠，平板无修饰中年女。]"
        }
    },
    {
        "id": "7abd6842-d22e-49ce-acde-370f51c2c51c",
        "name": "柔润贵妇",
        "language": "zh-CN",
        "gender": "female",
        "style": "温柔",
        "tags": [
            "有声书",
            "中年",
            "女中年",
            "35-46岁",
            "温柔"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "7abd6842-d22e-49ce-acde-370f51c2c51c",
            "age_stage": "mature",
            "age_min": 35,
            "age_max": 46,
            "accent": "standard-mandarin",
            "group": "女中年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "",
            "vv_style": "女中年",
            "vv_tags": [
                "女中年",
                "35-46岁"
            ],
            "persona": "[#设定：女声，年龄35-46岁。强制物理级锁定口腔圆腔包裹发声，圆润厚柔中频，彻底屏蔽扁薄声、尖锐声、干涩质感。声基底圆润醇厚，共鸣饱满柔和，咬字圆缓优雅，气质华贵温婉，圆腔优雅中年女声。]"
        }
    },
    {
        "id": "0406ec77-f23b-474a-aa8f-c0a82169dd75",
        "name": "破音浅嗓",
        "language": "zh-CN",
        "gender": "female",
        "style": "感伤·慵懒·自然",
        "tags": [
            "有声书",
            "中年",
            "女中年",
            "39-52岁",
            "感伤",
            "慵懒",
            "自然"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "0406ec77-f23b-474a-aa8f-c0a82169dd75",
            "age_stage": "mature",
            "age_min": 39,
            "age_max": 52,
            "accent": "standard-mandarin",
            "group": "女中年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "松垮破损、咬字断续的中年女声，衰老疲惫，适合有声书中的残破熟龄角色。",
            "vv_style": "女中年",
            "vv_tags": [
                "女中年",
                "39-52岁"
            ],
            "persona": "[#设定：女声，年龄39-52岁。强制物理级锁定老化声带破损基底，断续裂音频段，彻底屏蔽完整实声、紧实声质、年轻声基。声基底松垮残破，轻微裂音断层，咬字费力断续，气质衰老疲惫，老化残破中年女声。]"
        }
    },
    {
        "id": "4d003557-e648-4dbe-b8d7-f32d56fdfc4a",
        "name": "薄冷寡感",
        "language": "zh-CN",
        "gender": "female",
        "style": "高冷·沉稳·自然",
        "tags": [
            "有声书",
            "中年",
            "女中年",
            "31-42岁",
            "高冷",
            "沉稳",
            "自然"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "4d003557-e648-4dbe-b8d7-f32d56fdfc4a",
            "age_stage": "mature",
            "age_min": 31,
            "age_max": 42,
            "accent": "standard-mandarin",
            "group": "女中年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "单薄冷感、咬字短浅的中年女声，清冷孤绝，适合有声书中的寡淡女性角色。",
            "vv_style": "女中年",
            "vv_tags": [
                "女中年",
                "31-42岁"
            ],
            "persona": "[#设定：女声，年龄31-42岁。强制物理级锁定超薄冷感声基，无共鸣干声频段，彻底屏蔽厚润、暖调、包裹感共鸣。声基底单薄发冷，音色素净寡淡，咬字短浅疏离，气质清冷孤绝，薄嗓冷感中年女。]"
        }
    },
    {
        "id": "64fe7923-e851-4533-8427-1070b065887a",
        "name": "洪量大嗓",
        "language": "zh-CN",
        "gender": "female",
        "style": "活泼·热血·自然",
        "tags": [
            "有声书",
            "中年",
            "女中年",
            "34-47岁",
            "活泼",
            "热血",
            "自然"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "64fe7923-e851-4533-8427-1070b065887a",
            "age_stage": "mature",
            "age_min": 34,
            "age_max": 47,
            "accent": "standard-mandarin",
            "group": "女中年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "洪亮开阔、咬字外放的中年女声，爽朗豪迈，适合有声书中的大嗓女性角色。",
            "vv_style": "女中年",
            "vv_tags": [
                "女中年",
                "34-47岁"
            ],
            "persona": "[#设定：女声，年龄34-47岁。强制物理级锁定大开阔气量发声基底，强气息大共鸣，彻底屏蔽小气浅息、细弱声、拘束窄频。声基底洪亮开阔，声压极强，咬字大方外放，气质爽朗豪迈，大嗓洪亮中年女声。]"
        }
    },
    {
        "id": "13774548-e721-4df2-97b4-8a5ebdb86841",
        "name": "细柔弱嗓",
        "language": "zh-CN",
        "gender": "female",
        "style": "温柔·感伤·自然",
        "tags": [
            "有声书",
            "中年",
            "女中年",
            "32-43岁",
            "温柔",
            "感伤",
            "自然"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "13774548-e721-4df2-97b4-8a5ebdb86841",
            "age_stage": "mature",
            "age_min": 32,
            "age_max": 43,
            "accent": "standard-mandarin",
            "group": "女中年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "纤细窄柔、咬字收敛的中年女声，胆小温顺，适合有声书中的柔弱熟龄角色。",
            "vv_style": "女中年",
            "vv_tags": [
                "女中年",
                "32-43岁"
            ],
            "persona": "[#设定：女声，年龄32-43岁。强制物理级锁定狭腔窄幅发声基底，细弱窄频中音，彻底屏蔽开阔大嗓、洪亮外放、厚质声线。声基底纤细窄柔，音量偏弱，咬字细缓收敛，气质胆小温顺，细弱窄嗓中年女。]"
        }
    },
    {
        "id": "3b978460-5158-48c3-a4c6-90838df3a32e",
        "name": "混哑雾感",
        "language": "zh-CN",
        "gender": "female",
        "style": "慵懒·磁性·感伤",
        "tags": [
            "有声书",
            "中年",
            "女中年",
            "36-48岁",
            "慵懒",
            "磁性",
            "感伤"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "3b978460-5158-48c3-a4c6-90838df3a32e",
            "age_stage": "mature",
            "age_min": 36,
            "age_max": 48,
            "accent": "standard-mandarin",
            "group": "女中年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "朦胧混哑、咬字模糊的中年女声，暧昧有氛围，适合有声书中的雾哑女性角色。",
            "vv_style": "女中年",
            "vv_tags": [
                "女中年",
                "36-48岁"
            ],
            "persona": "[#设定：女声，年龄36-48岁。强制物理级锁定雾感混哑混合基底，半哑半柔特殊频段，彻底屏蔽纯亮纯哑、干净直白声质。声基底朦胧混浊，柔哑交织，咬字模糊舒缓，气质暧昧氛围感，雾哑小众中年女声。]"
        }
    },
    {
        "id": "599f93fe-642b-4a72-beb0-ac8a54c6982f",
        "name": "硬核女嗓",
        "language": "zh-CN",
        "gender": "female",
        "style": "霸总·热血·御姐感",
        "tags": [
            "有声书",
            "中年",
            "女中年",
            "35-46岁",
            "霸总",
            "热血",
            "御姐感"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "599f93fe-642b-4a72-beb0-ac8a54c6982f",
            "age_stage": "mature",
            "age_min": 35,
            "age_max": 46,
            "accent": "standard-mandarin",
            "group": "女中年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "刚硬紧实、咬字利落的中年女声，强势硬核，适合有声书中的力量型女性角色。",
            "vv_style": "女中年",
            "vv_tags": [
                "女中年",
                "35-46岁"
            ],
            "persona": "[#设定：女声，年龄35-46岁。强制物理级锁定硬质紧绷发声基底，高硬度实声，彻底屏蔽软绵松弛、柔媚气声、绵软共鸣。声基底刚硬紧实，发力感强，咬字坚硬利落，气质强势硬核，刚性力量型中年女嗓。]"
        }
    },
    {
        "id": "4e8b1774-5c6b-49a8-ba0a-d7a9c1380c41",
        "name": "暖沉女嗓",
        "language": "zh-CN",
        "gender": "female",
        "style": "温柔·沉稳·自然",
        "tags": [
            "有声书",
            "中年",
            "女中年",
            "30-41岁",
            "温柔",
            "沉稳",
            "自然"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "4e8b1774-5c6b-49a8-ba0a-d7a9c1380c41",
            "age_stage": "mature",
            "age_min": 30,
            "age_max": 41,
            "accent": "standard-mandarin",
            "group": "女中年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "暖糯厚实、咬字柔缓的中年女声，亲和治愈，适合有声书中的暖调熟龄角色。",
            "vv_style": "女中年",
            "vv_tags": [
                "女中年",
                "30-41岁"
            ],
            "persona": "[#设定：女声，年龄30-41岁。强制物理级锁定软糯暖调底层声基，暖频下沉中音，彻底屏蔽冷底薄声、冷锐高频、干涩声质。声基底暖糯厚实，自带暖意基底，咬字柔和黏缓，气质亲和治愈，暖糯底嗓中年女声。]"
        }
    },
    {
        "id": "82c3127c-aef8-48c5-9b04-6292020f2fac",
        "name": "脆利短嗓",
        "language": "zh-CN",
        "gender": "female",
        "style": "专业·轻快·自然",
        "tags": [
            "有声书",
            "中年",
            "女中年",
            "33-44岁",
            "专业",
            "轻快",
            "自然"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "82c3127c-aef8-48c5-9b04-6292020f2fac",
            "age_stage": "mature",
            "age_min": 33,
            "age_max": 44,
            "accent": "standard-mandarin",
            "group": "女中年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "短促清脆、咬字紧凑的中年女声，精明干练，适合有声书中的利落职场角色。",
            "vv_style": "女中年",
            "vv_tags": [
                "女中年",
                "33-44岁"
            ],
            "persona": "[#设定：女声，年龄33-44岁。强制物理级锁定短频脆裂发声基底，短音节快节奏频段，彻底屏蔽长腔拖音、慢缓沉音、绵长共鸣。声基底短促清脆，音节利落割裂，咬字快速紧凑，气质精明干练，短脆快频中年女嗓。]"
        }
    },
    {
        "id": "be8aa822-3739-4976-b406-9edfb2abb2d1",
        "name": "古腔戏韵",
        "language": "zh-CN",
        "gender": "female",
        "style": "古风·温柔·沉稳",
        "tags": [
            "有声书",
            "中年",
            "女中年",
            "37-49岁",
            "古风",
            "温柔",
            "沉稳"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "be8aa822-3739-4976-b406-9edfb2abb2d1",
            "age_stage": "mature",
            "age_min": 37,
            "age_max": 49,
            "accent": "standard-mandarin",
            "group": "女中年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "古典转腔、咬字含韵的中年女声，古雅端庄，适合有声书中的戏腔角色。",
            "vv_style": "女中年",
            "vv_tags": [
                "女中年",
                "37-49岁"
            ],
            "persona": "[#设定：女声，年龄37-49岁。强制物理级锁定古风戏腔原生基底，转韵婉折特殊声段，彻底屏蔽现代直白平嗓、生硬直音。声基底自带古典转腔，韵折绵长，咬字含腔带韵，气质古雅端庄，戏腔古韵中年女声。]"
        }
    },
    {
        "id": "a08b182b-d286-4c47-804c-7692cd689a3a",
        "name": "机械冷嗓",
        "language": "zh-CN",
        "gender": "female",
        "style": "高冷·专业·沉稳",
        "tags": [
            "有声书",
            "中年",
            "女中年",
            "32-45岁",
            "高冷",
            "专业",
            "沉稳"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "a08b182b-d286-4c47-804c-7692cd689a3a",
            "age_stage": "mature",
            "age_min": 32,
            "age_max": 45,
            "accent": "standard-mandarin",
            "group": "女中年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "金属冷感、咬字精准的中年女声，机械理性，适合有声书中的非人女性角色。",
            "vv_style": "女中年",
            "vv_tags": [
                "女中年",
                "32-45岁"
            ],
            "persona": "[#设定：女声，年龄32-45岁。强制物理级锁定金属冷调机械基底，无血肉平直频段，彻底屏蔽人声柔感、暖共鸣、情绪音色。声基底金属冷感，音色冰冷平直，咬字精准刻板，气质非人理性，金属机械中年女嗓。]"
        }
    },
    {
        "id": "14cdf454-bb8a-4377-b8d6-dafc5853a2dd",
        "name": "沉柔厚嗓",
        "language": "zh-CN",
        "gender": "female",
        "style": "温柔·感伤·磁性",
        "tags": [
            "有声书",
            "中年",
            "女中年",
            "38-51岁",
            "温柔",
            "感伤",
            "磁性"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "14cdf454-bb8a-4377-b8d6-dafc5853a2dd",
            "age_stage": "mature",
            "age_min": 38,
            "age_max": 51,
            "accent": "standard-mandarin",
            "group": "女中年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "湿柔厚重、咬字沉落的中年女声，阴郁温柔，适合有声书中的沉柔女性角色。",
            "vv_style": "女中年",
            "vv_tags": [
                "女中年",
                "38-51岁"
            ],
            "persona": "[#设定：女声，年龄38-51岁。强制物理级锁定湿地湿润声质基底，沉柔湿厚低频，彻底屏蔽干薄燥声、干裂烟嗓、干涩头腔音。声基底湿柔厚重，音色暗沉温润，咬字绵缓沉落，气质阴郁温柔，湿厚沉柔中年女声。]"
        }
    },
    {
        "id": "05bcac1f-76f7-4df6-8aaf-ff869db832d2",
        "name": "清寡禅寂",
        "language": "zh-CN",
        "gender": "female",
        "style": "高冷·沉稳·自然",
        "tags": [
            "有声书",
            "中年",
            "女中年",
            "34-46岁",
            "高冷",
            "沉稳",
            "自然"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "05bcac1f-76f7-4df6-8aaf-ff869db832d2",
            "age_stage": "mature",
            "age_min": 34,
            "age_max": 46,
            "accent": "standard-mandarin",
            "group": "女中年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "素净空寂、咬字无波的中年女声，无欲清冷，适合有声书中的禅寂女性角色。",
            "vv_style": "女中年",
            "vv_tags": [
                "女中年",
                "34-46岁"
            ],
            "persona": "[#设定：女声，年龄34-46岁。强制物理级锁定无色清寂原生声底，极简无修饰中频，彻底屏蔽浓艳媚嗓、厚重共鸣、高亮外放。声基底素净空寂，音色极淡，咬字轻敛无波，气质无欲清冷，禅寂清寡中年女声。]"
        }
    },
    {
        "id": "7ecb1553-b1ab-4841-877c-f0ea2f063508",
        "name": "暖心阿姨",
        "language": "zh-CN",
        "gender": "female",
        "style": "温柔·自然·沉稳",
        "tags": [
            "有声书",
            "老年",
            "女中年",
            "60-72岁",
            "温柔",
            "自然",
            "沉稳"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "7ecb1553-b1ab-4841-877c-f0ea2f063508",
            "age_stage": "senior",
            "age_min": 60,
            "age_max": 72,
            "accent": "standard-mandarin",
            "group": "女中年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "暖软温苍、咬字亲切的老年女声，慈祥和善，适合有声书中的暖心祖母角色。",
            "vv_style": "女中年",
            "vv_tags": [
                "女中年",
                "60-72岁"
            ],
            "persona": "[#设定：女声，年龄60-72岁。强制物理级锁定暖软温苍中低音，绵柔松缓声质，彻底屏蔽尖锐、刻薄、冷硬。声线软糯温和，咬字轻慢亲切，气质慈祥和善，适合邻家祖母、暖心长辈角色。]"
        }
    },
    {
        "id": "cb3db0dd-50fa-4533-9b27-cac184bfdc8d",
        "name": "沧桑老头",
        "language": "zh-CN",
        "gender": "male",
        "style": "磁性·慵懒·感伤",
        "tags": [
            "有声书",
            "老年",
            "男老年",
            "65-72岁",
            "磁性",
            "慵懒",
            "感伤"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "cb3db0dd-50fa-4533-9b27-cac184bfdc8d",
            "age_stage": "senior",
            "age_min": 65,
            "age_max": 72,
            "accent": "standard-mandarin",
            "group": "男老年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "沙哑沉厚、咬字松弛的老年男声，沧桑通透，适合有声书中的江湖老者角色。",
            "vv_style": "男老年",
            "vv_tags": [
                "男老年",
                "65-72岁"
            ],
            "persona": "[#设定：男声，年龄65-72岁。强制物理级锁定沙哑沉厚老年低音绝对频段，底层强制开启松弛胸腔共鸣与磨砂粗糙喉结发音，彻底屏蔽清亮尖锐、轻浮跳脱与虚弱气音感，全程沉稳厚重。赋予声音在低音区岁月基底上醇厚沙哑微颤的独特质感，声线粗哑磨砂感，带烟火气颗粒感，历经半生江湖气、通透世故，绝对禁止清亮、禁止稚嫩、禁止中年化伪音。咬字松弛厚重，语速平缓有力，尾音沉缓绵长，语气通透豁达，能在绝对纯正老年音域内完成淡然讲述、轻叹世事、温和劝诫、沧桑感慨，绝不尖锐刺耳、绝不轻浮躁动。市井老炮感、通透世故、看似散漫随性实则通透明理的老年男声，说话带贴耳沉厚呼吸感，用焊死老年低音的极致醇厚感，打造老街坊江湖故事感老年声线。]"
        }
    },
    {
        "id": "d862b190-975f-4d79-ac47-423da1fdc715",
        "name": "慈和老翁",
        "language": "zh-CN",
        "gender": "male",
        "style": "温柔·沉稳·自然",
        "tags": [
            "有声书",
            "老年",
            "男老年",
            "60-72岁",
            "温柔",
            "沉稳",
            "自然"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "d862b190-975f-4d79-ac47-423da1fdc715",
            "age_stage": "senior",
            "age_min": 60,
            "age_max": 72,
            "accent": "standard-mandarin",
            "group": "男老年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "温和迟缓、咬字轻柔的老年男声，慈祥亲切，适合有声书中的邻家老翁角色。",
            "vv_style": "男老年",
            "vv_tags": [
                "男老年",
                "60-72岁"
            ],
            "persona": "[#设定：男声，年龄60-72岁。强制物理级锁定暖调柔和苍音，松软弱沙哑质感，彻底屏蔽冷硬、尖锐、厚重压迫。声线温和迟缓，咬字轻缓柔和，气质慈祥亲切，适合邻家慈祥祖辈角色。]"
        }
    },
    {
        "id": "83714410-0f3d-4076-a3e7-584f262dbf10",
        "name": "威严长辈",
        "language": "zh-CN",
        "gender": "male",
        "style": "沉稳·霸总·专业",
        "tags": [
            "有声书",
            "老年",
            "男老年",
            "62-75岁",
            "沉稳",
            "霸总",
            "专业"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "83714410-0f3d-4076-a3e7-584f262dbf10",
            "age_stage": "senior",
            "age_min": 62,
            "age_max": 75,
            "accent": "standard-mandarin",
            "group": "男老年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "沉厚庄肃、咬字规整的老年男声，持重威严，适合有声书中的家族掌权长辈角色。",
            "vv_style": "男老年",
            "vv_tags": [
                "男老年",
                "62-75岁"
            ],
            "persona": "[#设定：男声，年龄62-75岁。强制物理级锁定沉厚庄肃老低音，紧实老化共鸣，彻底散漫、软糯、轻浮。声线端正威严，咬字沉稳规整，气质持重肃穆，适合家族长辈、老牌掌权老者角色。]"
        }
    },
    {
        "id": "14b2c33b-dec9-4abe-9ba9-d346d4e6fac4",
        "name": "儒雅学者",
        "language": "zh-CN",
        "gender": "male",
        "style": "专业·温柔·沉稳",
        "tags": [
            "有声书",
            "老年",
            "男老年",
            "60-70岁",
            "专业",
            "温柔",
            "沉稳"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "14b2c33b-dec9-4abe-9ba9-d346d4e6fac4",
            "age_stage": "senior",
            "age_min": 60,
            "age_max": 70,
            "accent": "standard-mandarin",
            "group": "男老年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "温文苍润、咬字端雅的老年男声，书卷儒雅，适合有声书中的退休教授角色。",
            "vv_style": "男老年",
            "vv_tags": [
                "男老年",
                "60-70岁"
            ],
            "persona": "[#设定：男声，年龄60-70岁。强制物理级锁定文雅清和老中音，平缓绵长气息，彻底屏蔽粗鄙、嘶哑、外放暴躁。声线温文苍润，咬字端雅清晰，气质书卷儒雅，适合退休教授、文人老先生。]"
        }
    },
    {
        "id": "04335bda-a3ef-4272-b3b0-2001aa49f9ab",
        "name": "硬朗老兵",
        "language": "zh-CN",
        "gender": "male",
        "style": "热血·沉稳·专业",
        "tags": [
            "有声书",
            "老年",
            "男老年",
            "63-76岁",
            "热血",
            "沉稳",
            "专业"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "04335bda-a3ef-4272-b3b0-2001aa49f9ab",
            "age_stage": "senior",
            "age_min": 63,
            "age_max": 76,
            "accent": "standard-mandarin",
            "group": "男老年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "苍劲有力、咬字坚定的老年男声，刚毅凛然，适合有声书中的退役老兵角色。",
            "vv_style": "男老年",
            "vv_tags": [
                "男老年",
                "63-76岁"
            ],
            "persona": "[#设定：男声，年龄63-76岁。强制物理级锁定刚劲厚实老声，硬朗紧致声质，彻底屏蔽软弱、虚浮、绵软无力。声线苍劲有力，咬字干脆坚定，气质刚毅凛然，适合退役老兵、硬核老年长辈。]"
        }
    },
    {
        "id": "6c443af7-91be-4619-8deb-8788f80ba30c",
        "name": "隐世老者",
        "language": "zh-CN",
        "gender": "male",
        "style": "古风·沉稳·自然",
        "tags": [
            "有声书",
            "老年",
            "男老年",
            "66-80岁",
            "古风",
            "沉稳",
            "自然"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "6c443af7-91be-4619-8deb-8788f80ba30c",
            "age_stage": "senior",
            "age_min": 66,
            "age_max": 80,
            "accent": "standard-mandarin",
            "group": "男老年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "空寂淡远、咬字舒缓的老年男声，清静无欲，适合有声书中的山林隐士角色。",
            "vv_style": "男老年",
            "vv_tags": [
                "男老年",
                "66-80岁"
            ],
            "persona": "[#设定：男声，年龄66-80岁。强制物理级锁定空寂淡远老低音，平缓空灵气息，彻底屏蔽市侩、浑浊、强势凌厉。声线淡然悠长，咬字从容舒缓，气质清静无欲，适合修道隐士、山林修行老者。]"
        }
    },
    {
        "id": "411660eb-8f83-4fa9-a9d3-6fb5f0b2a255",
        "name": "市井老农",
        "language": "zh-CN",
        "gender": "male",
        "style": "自然·沉稳·活泼",
        "tags": [
            "有声书",
            "老年",
            "男老年",
            "61-73岁",
            "自然",
            "沉稳",
            "活泼"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "411660eb-8f83-4fa9-a9d3-6fb5f0b2a255",
            "age_stage": "senior",
            "age_min": 61,
            "age_max": 73,
            "accent": "standard-mandarin",
            "group": "男老年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "质朴粗实、咬字直白的老年男声，乡土淳朴，适合有声书中的务农老人角色。",
            "vv_style": "男老年",
            "vv_tags": [
                "男老年",
                "61-73岁"
            ],
            "persona": "[#设定：男声，年龄61-73岁。强制物理级锁定质朴土味老中音，接地气厚实声感，彻底屏蔽精致、冷感、刻意修饰。声线朴实粗实，咬字直白拖沓，气质乡土淳朴，适合务农老人、市井普通老者。]"
        }
    },
    {
        "id": "55db17af-b99e-4391-8923-dd46f5131e40",
        "name": "说书艺人",
        "language": "zh-CN",
        "gender": "male",
        "style": "古风·专业·活泼",
        "tags": [
            "有声书",
            "老年",
            "男老年",
            "62-74岁",
            "古风",
            "专业",
            "活泼"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "55db17af-b99e-4391-8923-dd46f5131e40",
            "age_stage": "senior",
            "age_min": 62,
            "age_max": 74,
            "accent": "standard-mandarin",
            "group": "男老年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "苍亮起伏、咬字古韵的老年男声，老练鲜活，适合有声书中的传统说书艺人角色。",
            "vv_style": "男老年",
            "vv_tags": [
                "男老年",
                "62-74岁"
            ],
            "persona": "[#设定：男声，年龄62-74岁。强制物理级锁定苍亮起伏老声，老式腔调震颤质感，彻底屏蔽平淡、沉闷、低沉压抑。声线抑扬顿挫，咬字古韵浓郁，气质老练鲜活，适合传统说书、民俗老艺人。]"
        }
    },
    {
        "id": "a2775762-b68e-41a9-ab55-2b327b560d28",
        "name": "豁达老汉",
        "language": "zh-CN",
        "gender": "male",
        "style": "活泼·自然·温柔",
        "tags": [
            "有声书",
            "老年",
            "男老年",
            "60-71岁",
            "活泼",
            "自然",
            "温柔"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "a2775762-b68e-41a9-ab55-2b327b560d28",
            "age_stage": "senior",
            "age_min": 60,
            "age_max": 71,
            "accent": "standard-mandarin",
            "group": "男老年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "洪亮松弛、咬字爽朗的老年男声，乐观豁达，适合有声书中的开朗长辈角色。",
            "vv_style": "男老年",
            "vv_tags": [
                "男老年",
                "60-71岁"
            ],
            "persona": "[#设定：男声，年龄60-71岁。强制物理级锁定敞亮苍亮老声，松弛外放气息，彻底阴郁、沉郁、压抑沙哑。声线洪亮松弛，咬字爽朗随性，气质乐观开朗，适合心态豁达、爱笑老年长辈。]"
        }
    },
    {
        "id": "ca2d75e4-a734-4027-993b-0bd35b8e627e",
        "name": "严肃老翁",
        "language": "zh-CN",
        "gender": "male",
        "style": "专业·沉稳·高冷",
        "tags": [
            "有声书",
            "老年",
            "男老年",
            "63-75岁",
            "专业",
            "沉稳",
            "高冷"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "ca2d75e4-a734-4027-993b-0bd35b8e627e",
            "age_stage": "senior",
            "age_min": 63,
            "age_max": 75,
            "accent": "standard-mandarin",
            "group": "男老年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "生硬端正、咬字严谨的老年男声，古板严苛，适合有声书中的规矩派老者角色。",
            "vv_style": "男老年",
            "vv_tags": [
                "男老年",
                "63-75岁"
            ],
            "persona": "[#设定：男声，年龄63-75岁。强制物理级锁定刻板刚正老中音，严肃紧绷声质，彻底散漫、慵懒、软糯随性。声线生硬端正，咬字严谨刻板，气质古板严苛，适合旧式严长、规矩派老者。]"
        }
    },
    {
        "id": "79c8da73-c8d4-4107-98e0-c89783d9df0c",
        "name": "沉情老者",
        "language": "zh-CN",
        "gender": "male",
        "style": "感伤·慵懒·磁性",
        "tags": [
            "有声书",
            "老年",
            "男老年",
            "64-78岁",
            "感伤",
            "慵懒",
            "磁性"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "79c8da73-c8d4-4107-98e0-c89783d9df0c",
            "age_stage": "senior",
            "age_min": 64,
            "age_max": 78,
            "accent": "standard-mandarin",
            "group": "男老年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "复古闷哑、咬字悠长的老年男声，感伤念旧，适合有声书中的深情追忆角色。",
            "vv_style": "男老年",
            "vv_tags": [
                "男老年",
                "64-78岁"
            ],
            "persona": "[#设定：男声，年龄64-78岁。强制物理级锁定复古闷哑老低音，绵长怀旧气息，彻底屏蔽鲜活、高亮、轻快跳脱。声线沉缓怀旧，咬字悠长缓慢，气质感伤念旧，适合追忆过往、深情老年人设。]"
        }
    },
    {
        "id": "8c8feac0-2128-472a-81c7-d88ec21d3334",
        "name": "粗粝老者",
        "language": "zh-CN",
        "gender": "male",
        "style": "磁性·自然·慵懒",
        "tags": [
            "有声书",
            "老年",
            "男老年",
            "66-79岁",
            "磁性",
            "自然",
            "慵懒"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "8c8feac0-2128-472a-81c7-d88ec21d3334",
            "age_stage": "senior",
            "age_min": 66,
            "age_max": 79,
            "accent": "standard-mandarin",
            "group": "男老年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "风沙粗哑、咬字散漫的老年男声，野性质朴，适合有声书中的山野老者角色。",
            "vv_style": "男老年",
            "vv_tags": [
                "男老年",
                "66-79岁"
            ],
            "persona": "[#设定：男声，年龄66-79岁。强制物理级锁定风沙干裂老哑声，旷野粗糙声质，彻底细腻、温润、精致柔和。声线粗哑开阔，咬字随性散漫，气质野性质朴，适合山野独居、游牧风老年角色。]"
        }
    },
    {
        "id": "cc343aca-8390-4518-aa2b-20a5ed3d9703",
        "name": "温厚医师",
        "language": "zh-CN",
        "gender": "male",
        "style": "温柔·专业·沉稳",
        "tags": [
            "有声书",
            "老年",
            "男老年",
            "60-70岁",
            "温柔",
            "专业",
            "沉稳"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "cc343aca-8390-4518-aa2b-20a5ed3d9703",
            "age_stage": "senior",
            "age_min": 60,
            "age_max": 70,
            "accent": "standard-mandarin",
            "group": "男老年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "清润平和、咬字耐心的老年男声，仁厚温和，适合有声书中的老医师角色。",
            "vv_style": "男老年",
            "vv_tags": [
                "男老年",
                "60-70岁"
            ],
            "persona": "[#设定：男声，年龄60-70岁。强制物理级锁定清润平和老中音，稳定舒缓气息，彻底凌厉、粗狂、破音沙哑。声线柔和沉稳，咬字耐心平缓，气质仁厚温和，适合老中医、退休医护老者。]"
        }
    },
    {
        "id": "73420d59-fde0-4529-ba6f-ac79ca25447c",
        "name": "倔强老叟",
        "language": "zh-CN",
        "gender": "male",
        "style": "霸总·热血·自然",
        "tags": [
            "有声书",
            "老年",
            "男老年",
            "62-74岁",
            "霸总",
            "热血",
            "自然"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "73420d59-fde0-4529-ba6f-ac79ca25447c",
            "age_stage": "senior",
            "age_min": 62,
            "age_max": 74,
            "accent": "standard-mandarin",
            "group": "男老年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "硬哑急躁、咬字生硬的老年男声，执拗倔强，适合有声书中的刚烈老者角色。",
            "vv_style": "男老年",
            "vv_tags": [
                "男老年",
                "62-74岁"
            ],
            "persona": "[#设定：男声，年龄62-74岁。强制物理级锁定硬厉糙裂老声，紧绷老化破音质感，彻底绵软、顺从、温润柔和。声线硬哑急躁，咬字用力生硬，气质执拗倔强，适合脾气固执、刚烈老年角色。]"
        }
    },
    {
        "id": "0aad2392-ad6d-4f3f-b434-969f61394dca",
        "name": "书香先生",
        "language": "zh-CN",
        "gender": "male",
        "style": "古风·专业·沉稳",
        "tags": [
            "有声书",
            "老年",
            "男老年",
            "64-76岁",
            "古风",
            "专业",
            "沉稳"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "0aad2392-ad6d-4f3f-b434-969f61394dca",
            "age_stage": "senior",
            "age_min": 64,
            "age_max": 76,
            "accent": "standard-mandarin",
            "group": "男老年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "端凝苍雅、咬字古韵的老年男声，端庄谦和，适合有声书中的私塾先生角色。",
            "vv_style": "男老年",
            "vv_tags": [
                "男老年",
                "64-76岁"
            ],
            "persona": "[#设定：男声，年龄64-76岁。强制物理级锁定古韵苍劲老中音，文雅沉厚气息，彻底市井粗鄙、外放喧闹。声线端凝苍雅，咬字古韵规整，气质端庄谦和，适合私塾先生、古风文人老者。]"
        }
    },
    {
        "id": "f812b4fd-4b1c-4601-bfa0-8d13aa2f1d91",
        "name": "潮哑水手",
        "language": "zh-CN",
        "gender": "male",
        "style": "磁性·慵懒·感伤",
        "tags": [
            "有声书",
            "老年",
            "男老年",
            "65-80岁",
            "磁性",
            "慵懒",
            "感伤"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "f812b4fd-4b1c-4601-bfa0-8d13aa2f1d91",
            "age_stage": "senior",
            "age_min": 65,
            "age_max": 80,
            "accent": "standard-mandarin",
            "group": "男老年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "沉厚潮哑、咬字低沉的老年男声，深沉内敛，适合有声书中的远洋老水手角色。",
            "vv_style": "男老年",
            "vv_tags": [
                "男老年",
                "65-80岁"
            ],
            "persona": "[#设定：男声，年龄65-80岁。强制物理级锁定潮湿厚哑老低音，沉浊海韵质感，彻底干爽、清亮、单薄通透。声线沉厚潮哑，咬字缓慢低沉，气质深沉内敛，适合常年出海、阅历深海老者。]"
        }
    },
    {
        "id": "e091003d-4814-4165-b155-23ea373b2caa",
        "name": "孤寂老者",
        "language": "zh-CN",
        "gender": "male",
        "style": "感伤·高冷·慵懒",
        "tags": [
            "有声书",
            "老年",
            "男老年",
            "67-81岁",
            "感伤",
            "高冷",
            "慵懒"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "e091003d-4814-4165-b155-23ea373b2caa",
            "age_stage": "senior",
            "age_min": 67,
            "age_max": 81,
            "accent": "standard-mandarin",
            "group": "男老年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "枯冷单薄、咬字零落的老年男声，落寞孤寂，适合有声书中的孤独暮年角色。",
            "vv_style": "男老年",
            "vv_tags": [
                "男老年",
                "67-81岁"
            ],
            "persona": "[#设定：男声，年龄67-81岁。强制物理级锁定枯冷单薄老声，衰败浅弱气息，彻底饱满、温暖、浑厚共鸣。声线萧瑟低沉，咬字零落缓慢，气质落寞孤寂，适合晚景凄凉、孤独暮年角色。]"
        }
    },
    {
        "id": "28e49522-0a8f-4aeb-902e-3b4d2afb2e74",
        "name": "枯哑老妪",
        "language": "zh-CN",
        "gender": "female",
        "style": "感伤·慵懒·自然",
        "tags": [
            "有声书",
            "老年",
            "女老年",
            "65-79岁",
            "感伤",
            "慵懒",
            "自然"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "28e49522-0a8f-4aeb-902e-3b4d2afb2e74",
            "age_stage": "senior",
            "age_min": 65,
            "age_max": 79,
            "accent": "standard-mandarin",
            "group": "女老年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "干枯浅哑、咬字断续的老年女声，沧桑衰弱，适合有声书中的风霜老妇角色。",
            "vv_style": "女老年",
            "vv_tags": [
                "女老年",
                "65-79岁"
            ],
            "persona": "[#设定：女声，年龄65-79岁。强制物理级锁定干枯浅哑老化声线，单薄干涩颗粒感，彻底屏蔽圆润、高亮、厚实。声线枯涩轻缓，咬字细微断续，气质沧桑衰弱，适合年迈体弱、风霜老人角色。]"
        }
    },
    {
        "id": "41b17389-7cb7-43d9-b3c8-23f2d1b32c01",
        "name": "硬朗老太",
        "language": "zh-CN",
        "gender": "female",
        "style": "热血·自然·专业",
        "tags": [
            "有声书",
            "老年",
            "女老年",
            "63-76岁",
            "热血",
            "自然",
            "专业"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "41b17389-7cb7-43d9-b3c8-23f2d1b32c01",
            "age_stage": "senior",
            "age_min": 63,
            "age_max": 76,
            "accent": "standard-mandarin",
            "group": "女老年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "清亮有力、咬字爽快的老年女声，要强干练，适合有声书中的硬朗老太角色。",
            "vv_style": "女老年",
            "vv_tags": [
                "女老年",
                "63-76岁"
            ],
            "persona": "[#设定：女声，年龄63-76岁。强制物理级锁定刚劲紧实老化声线，利落硬朗质感，彻底屏蔽软弱、绵软、怯懦。声线清亮有力，咬字干脆爽快，气质要强干练，适合一辈子操劳、烈性老年女性。]"
        }
    },
    {
        "id": "090a1f2f-7584-4c28-aebd-e2a2ad3bb11f",
        "name": "淳朴老太",
        "language": "zh-CN",
        "gender": "female",
        "style": "自然·温柔·沉稳",
        "tags": [
            "有声书",
            "老年",
            "女老年",
            "61-73岁",
            "自然",
            "温柔",
            "沉稳"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "090a1f2f-7584-4c28-aebd-e2a2ad3bb11f",
            "age_stage": "senior",
            "age_min": 61,
            "age_max": 73,
            "accent": "standard-mandarin",
            "group": "女老年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "朴实粗柔、咬字松弛的老年女声，乡土淳朴，适合有声书中的务农老太角色。",
            "vv_style": "女老年",
            "vv_tags": [
                "女老年",
                "61-73岁"
            ],
            "persona": "[#设定：女声，年龄61-73岁。强制物理级锁定乡土平实老声，质朴厚实自然质感，彻底屏蔽精致、冷感、刻意修饰。声线朴实粗柔，咬字直白松弛，气质乡土淳朴，适合乡村务农、朴素老人。]"
        }
    },
    {
        "id": "a287e5a1-4483-4cc5-997c-c7bf0302d899",
        "name": "孱弱老妇",
        "language": "zh-CN",
        "gender": "female",
        "style": "感伤·温柔·自然",
        "tags": [
            "有声书",
            "老年",
            "女老年",
            "64-78岁",
            "感伤",
            "温柔",
            "自然"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "a287e5a1-4483-4cc5-997c-c7bf0302d899",
            "age_stage": "senior",
            "age_min": 64,
            "age_max": 78,
            "accent": "standard-mandarin",
            "group": "女老年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "细柔断续、咬字无力的老年女声，体弱萎靡，适合有声书中的病弱老妇角色。",
            "vv_style": "女老年",
            "vv_tags": [
                "女老年",
                "64-78岁"
            ],
            "persona": "[#设定：女声，年龄64-78岁。强制物理级锁定虚浅细弱气声，气短轻飘老化质感，彻底屏蔽洪亮、紧实、高亮。声线细柔断续，咬字轻浅无力，气质体弱萎靡，适合常年病弱、年迈体虚人设。]"
        }
    },
    {
        "id": "6909b68e-8641-4136-b76e-66743bde4125",
        "name": "戏曲老伶",
        "language": "zh-CN",
        "gender": "female",
        "style": "古风·温柔·专业",
        "tags": [
            "有声书",
            "老年",
            "女老年",
            "62-74岁",
            "古风",
            "温柔",
            "专业"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "6909b68e-8641-4136-b76e-66743bde4125",
            "age_stage": "senior",
            "age_min": 62,
            "age_max": 74,
            "accent": "standard-mandarin",
            "group": "女老年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "苍润婉转、咬字含韵的老年女声，古典温婉，适合有声书中的戏曲老艺人角色。",
            "vv_style": "女老年",
            "vv_tags": [
                "女老年",
                "62-74岁"
            ],
            "persona": "[#设定：女声，年龄62-74岁。强制物理级锁定苍润婉转老声，唱腔余韵质感，彻底屏蔽生硬、沉闷、直白粗粝。声线婉转悠长，咬字含韵起伏，气质古典温婉，适合戏曲老艺人、传统古风老太。]"
        }
    },
    {
        "id": "1374d52b-2fcb-40f6-9a6a-0efa0a2ab83b",
        "name": "孤僻老妪",
        "language": "zh-CN",
        "gender": "female",
        "style": "高冷·沉稳·自然",
        "tags": [
            "有声书",
            "老年",
            "女老年",
            "65-77岁",
            "高冷",
            "沉稳",
            "自然"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "1374d52b-2fcb-40f6-9a6a-0efa0a2ab83b",
            "age_stage": "senior",
            "age_min": 65,
            "age_max": 77,
            "accent": "standard-mandarin",
            "group": "女老年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "冷薄寡淡、咬字疏离的老年女声，孤清寡言，适合有声书中的独居老妪角色。",
            "vv_style": "女老年",
            "vv_tags": [
                "女老年",
                "65-77岁"
            ],
            "persona": "[#设定：女声，年龄65-77岁。强制物理级锁定冷薄寡淡老中音，干涩清冷气息，彻底屏蔽软糯、热情、暖调治愈。声线冷淡平缓，咬字简短疏离，气质孤清寡言，适合独居孤僻、冷漠老年女性。]"
        }
    },
    {
        "id": "74bffc3b-ae15-4c2b-aeec-0e2ace9905cf",
        "name": "开朗老太",
        "language": "zh-CN",
        "gender": "female",
        "style": "活泼·轻快·自然",
        "tags": [
            "有声书",
            "老年",
            "女老年",
            "60-71岁",
            "活泼",
            "轻快",
            "自然"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "74bffc3b-ae15-4c2b-aeec-0e2ace9905cf",
            "age_stage": "senior",
            "age_min": 60,
            "age_max": 71,
            "accent": "standard-mandarin",
            "group": "女老年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "明朗轻快、咬字爽朗的老年女声，乐观豁达，适合有声书中的健朗老太角色。",
            "vv_style": "女老年",
            "vv_tags": [
                "女老年",
                "60-71岁"
            ],
            "persona": "[#设定：女声，年龄60-71岁。强制物理级锁定敞亮松弛苍音，轻快外放气息，彻底阴郁、沉郁、压抑沙哑。声线明朗轻快，咬字爽朗随性，气质乐观豁达，适合心态年轻、爱笑健朗老人。]"
        }
    },
    {
        "id": "fbc521d0-d32a-4e7f-a865-9ffdc08540d0",
        "name": "古板老妇",
        "language": "zh-CN",
        "gender": "female",
        "style": "专业·沉稳·高冷",
        "tags": [
            "有声书",
            "老年",
            "女老年",
            "63-75岁",
            "专业",
            "沉稳",
            "高冷"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "fbc521d0-d32a-4e7f-a865-9ffdc08540d0",
            "age_stage": "senior",
            "age_min": 63,
            "age_max": 75,
            "accent": "standard-mandarin",
            "group": "女老年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "生硬端正、咬字刻板的老年女声，严苛古板，适合有声书中的旧式严母角色。",
            "vv_style": "女老年",
            "vv_tags": [
                "女老年",
                "63-75岁"
            ],
            "persona": "[#设定：女声，年龄63-75岁。强制物理级锁定紧绷严肃老中音，刻板端正声质，彻底散漫、慵懒、柔和随性。声线生硬端正，咬字严谨刻板，气质严苛古板，适合旧式严母、规矩深重长辈。]"
        }
    },
    {
        "id": "51686237-2d14-48d6-b193-080786d6681d",
        "name": "温柔老媪",
        "language": "zh-CN",
        "gender": "female",
        "style": "温柔·沉稳",
        "tags": [
            "有声书",
            "老年",
            "女老年",
            "64-78岁",
            "温柔",
            "沉稳"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "51686237-2d14-48d6-b193-080786d6681d",
            "age_stage": "senior",
            "age_min": 64,
            "age_max": 78,
            "accent": "standard-mandarin",
            "group": "女老年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "温柔怀旧的老年女声，语速舒缓，适合有声书中的慈爱长辈与往事回忆。",
            "vv_style": "女老年",
            "vv_tags": [
                "女老年",
                "64-78岁"
            ],
            "persona": "[#设定：女声，年龄64-78岁。强制物理级锁定复古柔哑低中音，绵长怀旧气息，彻底屏蔽尖锐、轻快、跳脱鲜活。声线沉缓温柔，咬字悠长舒缓，气质温婉念旧，适合追忆往事、深情老年人设。]"
        }
    },
    {
        "id": "2ed8127b-64f2-4d08-8a68-d47ed6935bd1",
        "name": "精明婆婆",
        "language": "zh-CN",
        "gender": "female",
        "style": "自然",
        "tags": [
            "有声书",
            "老年",
            "女老年",
            "61-72岁",
            "自然"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "2ed8127b-64f2-4d08-8a68-d47ed6935bd1",
            "age_stage": "senior",
            "age_min": 61,
            "age_max": 72,
            "accent": "standard-mandarin",
            "group": "女老年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "利落精明的老年女声，咬字清楚，适合有声书中的市井长辈与持家角色。",
            "vv_style": "女老年",
            "vv_tags": [
                "女老年",
                "61-72岁"
            ],
            "persona": "[#设定：女声，年龄61-72岁。强制物理级锁定清亮干练老化声线，利落紧凑咬字质感，彻底迟钝、浑浊、软弱。声线利落通透，咬字灵巧直白，气质世故精明，适合市井老街、持家老练老太太。]"
        }
    },
    {
        "id": "e0cdc2a9-aa7e-4df1-a760-bafcabd3938b",
        "name": "山野老妪",
        "language": "zh-CN",
        "gender": "female",
        "style": "自然",
        "tags": [
            "有声书",
            "老年",
            "女老年",
            "66-79岁",
            "自然"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "e0cdc2a9-aa7e-4df1-a760-bafcabd3938b",
            "age_stage": "senior",
            "age_min": 66,
            "age_max": 79,
            "accent": "standard-mandarin",
            "group": "女老年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "粗哑质朴的老年女声，带有山野生活气息，适合有声书中的乡野长者。",
            "vv_style": "女老年",
            "vv_tags": [
                "女老年",
                "66-79岁"
            ],
            "persona": "[#设定：女声，年龄66-79岁。强制物理级锁定风沙粗哑老化声线，旷野干燥质感，彻底细腻、柔润、精致柔和。声线粗哑松弛，咬字随性散漫，气质山野质朴，适合深山独居、劳作一生老人。]"
        }
    },
    {
        "id": "c41e0cfb-c1f0-4845-b567-5313563d1f22",
        "name": "仁心医者",
        "language": "zh-CN",
        "gender": "female",
        "style": "温柔·沉稳",
        "tags": [
            "有声书",
            "老年",
            "女老年",
            "60-70岁",
            "温柔",
            "沉稳"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "c41e0cfb-c1f0-4845-b567-5313563d1f22",
            "age_stage": "senior",
            "age_min": 60,
            "age_max": 70,
            "accent": "standard-mandarin",
            "group": "女老年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "沉静仁厚的老年女声，表达耐心亲和，适合有声书中的医者与可靠长辈。",
            "vv_style": "女老年",
            "vv_tags": [
                "女老年",
                "60-70岁"
            ],
            "persona": "[#设定：女声，年龄60-70岁。强制物理级锁定清润平稳老中音，柔和稳定气息，彻底尖利、暴躁、破音嘈杂。声线沉静温柔，咬字耐心平缓，气质仁厚亲和，适合老中医、资深护理长辈。]"
        }
    },
    {
        "id": "54d41788-ec0a-4a0a-af47-fef9f979fdd1",
        "name": "执拗老太",
        "language": "zh-CN",
        "gender": "female",
        "style": "女老年",
        "tags": [
            "有声书",
            "老年",
            "女老年",
            "62-74岁"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "54d41788-ec0a-4a0a-af47-fef9f979fdd1",
            "age_stage": "senior",
            "age_min": 62,
            "age_max": 74,
            "accent": "standard-mandarin",
            "group": "女老年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "紧锐强势的老年女声，咬字较真，适合有声书中性格执拗的长辈角色。",
            "vv_style": "女老年",
            "vv_tags": [
                "女老年",
                "62-74岁"
            ],
            "persona": "[#设定：女声，年龄62-74岁。强制物理级锁定薄利偏紧老化声线，尖锐单薄颗粒感，彻底软糯、宽厚、温和包容。声线偏紧锐利，咬字较真生硬，气质执拗强势，适合性格较真、强势老年女性。]"
        }
    },
    {
        "id": "03b3cdfe-1cc6-4e7c-b9ca-175802a8505c",
        "name": "书香夫人",
        "language": "zh-CN",
        "gender": "female",
        "style": "温柔",
        "tags": [
            "有声书",
            "老年",
            "女老年",
            "64-76岁",
            "温柔"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "03b3cdfe-1cc6-4e7c-b9ca-175802a8505c",
            "age_stage": "senior",
            "age_min": 64,
            "age_max": 76,
            "accent": "standard-mandarin",
            "group": "女老年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "温雅端庄的老年女声，带有古韵与书卷气，适合有声书中的文雅长辈。",
            "vv_style": "女老年",
            "vv_tags": [
                "女老年",
                "64-76岁"
            ],
            "persona": "[#设定：女声，年龄64-76岁。强制物理级锁定古韵温雅低中音，沉缓文雅气息，彻底市井聒噪、粗鄙外放。声线端凝柔和，咬字文雅规整，气质温婉端庄，适合书香世家、旧式文雅长辈。]"
        }
    },
    {
        "id": "15f3b9b3-1741-4209-a920-d5bd72420612",
        "name": "温婉老缊",
        "language": "zh-CN",
        "gender": "female",
        "style": "温柔",
        "tags": [
            "有声书",
            "老年",
            "女老年",
            "65-80岁",
            "温柔"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "15f3b9b3-1741-4209-a920-d5bd72420612",
            "age_stage": "senior",
            "age_min": 65,
            "age_max": 80,
            "accent": "standard-mandarin",
            "group": "女老年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "柔婉细腻的老年女声，语气轻缓，适合有声书中的江南长辈与温情叙事。",
            "vv_style": "女老年",
            "vv_tags": [
                "女老年",
                "65-80岁"
            ],
            "persona": "[#设定：女声，年龄65-80岁。强制物理级锁定软糯柔婉老化声线，温润水乡质感，彻底硬朗、冷冽、厚重紧绷。声线柔婉轻缓，咬字细软柔和，气质温婉柔情，适合江南水乡、温婉南方老太。]"
        }
    },
    {
        "id": "8af9d875-5faa-4c5d-8fb4-ac174a13d30c",
        "name": "落寞老妇",
        "language": "zh-CN",
        "gender": "female",
        "style": "女老年",
        "tags": [
            "有声书",
            "老年",
            "女老年",
            "67-81岁"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "8af9d875-5faa-4c5d-8fb4-ac174a13d30c",
            "age_stage": "senior",
            "age_min": 67,
            "age_max": 81,
            "accent": "standard-mandarin",
            "group": "女老年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "低沉萧瑟的老年女声，情绪克制，适合有声书中的孤寂长者与沉重往事。",
            "vv_style": "女老年",
            "vv_tags": [
                "女老年",
                "67-81岁"
            ],
            "persona": "[#设定：女声，年龄67-81岁。强制物理级锁定萧瑟枯冷薄声，衰败低沉气息，彻底饱满、明媚、温润治愈。声线低沉萧瑟，咬字零落缓慢，气质孤寂落寞，适合晚景凄凉、心事深沉老年角色。]"
        }
    },
    {
        "id": "5a8a8124-d896-460f-b415-506b503339b5",
        "name": "慈祥奶奶",
        "language": "zh-CN",
        "gender": "female",
        "style": "温柔·自然·沉稳",
        "tags": [
            "有声书",
            "老年",
            "女老年",
            "80-90岁",
            "温柔",
            "自然",
            "沉稳"
        ],
        "extra": {
            "provider": "mossland",
            "provider_speaker": "5a8a8124-d896-460f-b415-506b503339b5",
            "age_stage": "senior",
            "age_min": 80,
            "age_max": 90,
            "accent": "standard-mandarin",
            "group": "女老年",
            "catalog_category": "有声书",
            "source_type": "user_clone",
            "profile_source": "vv_clone_catalog",
            "description": "苍老温润、舒缓慈祥的高龄女声，带岁月颗粒感，适合有声书长辈角色。",
            "vv_style": "女老年",
            "vv_tags": [
                "女老年",
                "80-90岁"
            ],
            "persona": "[#设定：女声，年龄80-90岁。强制物理级锁定苍老温润超高龄低音绝对频段，底层强制开启轻柔松弛胸腔共鸣与磨砂温润喉结发音，彻底屏蔽清亮尖锐、浮躁跳脱与稚嫩单薄感，全程温和慈祥。赋予声音在低音区岁月基底上沙哑平缓、暖意绵长的独特质感，声线苍老柔和，带烟火褶皱颗粒感，历经世事温和通透，绝对禁止清亮、禁止稚嫩、禁止中年化伪音。咬字松弛平缓，语速缓慢柔和，尾音轻缓绵长，语气温润慈祥，能在绝对纯正超高龄音域内完成淡然讲述、轻声轻叹、耐心劝慰、温柔感慨，绝不尖锐刺耳、绝不轻浮躁动。慈祥祖母感、温和包容、看似苍老迟缓实则满心暖意的高龄女声，说话带贴耳轻柔呼吸感，用焊死高龄低音的极致温润感，打造家常岁月温情故事感高龄声线。]"
        }
    }
];

function trimText(value) {
    return String(value || "").replace(/^\s+|\s+$/g, "");
}

function baseUrl(options) {
    return trimText(options && options.baseUrl || MOSS_DEFAULT_BASE_URL).replace(/\/+$/, "");
}

function outputFormat(options) {
    return trimText(options && options.outputFormat).toLowerCase() === "wav" ? "wav" : "mp3";
}

function options() {
    return [
        { key: "apiKey", label: "Mossland API Key", type: "password", defaultValue: "" },
        { key: "baseUrl", label: "服务地址", type: "text", defaultValue: MOSS_DEFAULT_BASE_URL },
        { key: "version", label: "模型版本（可选）", type: "text", defaultValue: "" },
        {
            key: "outputFormat",
            label: "合成格式",
            type: "select",
            defaultValue: "mp3",
            values: [
                { label: "MP3", value: "mp3" },
                { label: "WAV", value: "wav" }
            ]
        },
        { key: "timeout", label: "超时（秒）", type: "number", defaultValue: "120" }
    ];
}

function voices(options, ctx) {
    return MOSS_VOICES;
}

function synthesize(text, voice, params, options, ctx) {
    var apiKey = trimText(options && options.apiKey);
    if (!apiKey) throw "请先填写 Mossland API Key";
    var voiceId = trimText(voice && voice.id);
    if (!voiceId) throw "请先选择 Mossland 发音人";
    var format = outputFormat(options);
    var payload = {
        model: "moss-tts",
        input: String(text || ""),
        voice_id: voiceId,
        response_format: format,
        delivery_method: "audio"
    };
    var version = trimText(options && options.version);
    if (version) payload.version = version;
    return {
        url: baseUrl(options) + "/v1/audio/speech",
        method: "POST",
        headers: {
            Authorization: "Bearer " + apiKey,
            "Content-Type": "application/json"
        },
        requestContentType: "application/json",
        body: JSON.stringify(payload),
        audioContentType: format === "wav" ? "audio/wav" : "audio/mpeg",
        timeout: Number(options && options.timeout || 120),
        retry: 1
    };
}
